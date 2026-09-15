use jni::{
    JNIEnv,
    objects::{JObject, JString},
    sys::{jfloat, jint, jintArray},
};
use resvg::{tiny_skia, usvg};
use std::{
    collections::VecDeque,
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
    sync::{Mutex, OnceLock},
};

struct Layout {
    source: String,
    width: u32,
    tree: usvg::Tree,
}

#[derive(Debug)]
enum Failure {
    Input(String),
    Internal(String),
}
impl From<String> for Failure {
    fn from(message: String) -> Self {
        Self::Input(message)
    }
}
impl From<&str> for Failure {
    fn from(message: &str) -> Self {
        Self::Input(message.into())
    }
}

#[derive(Default)]
struct Engine {
    renderer: md2svg::Renderer,
    layouts: VecDeque<Layout>,
}

impl Engine {
    fn render(
        &mut self,
        source: &str,
        layout_width: u32,
        width: u32,
        height: u32,
        x: f32,
        y: f32,
    ) -> Result<Vec<i32>, Failure> {
        if !(64..=4096).contains(&layout_width)
            || width == 0
            || width > 2048
            || height == 0
            || height > 4096
        {
            return Err("Markdown 渲染尺寸超出范围".into());
        }
        if !x.is_finite() || !y.is_finite() {
            return Err("Markdown 位移必须是有限数值".into());
        }
        let index = self
            .layouts
            .iter()
            .position(|l| l.source == source && l.width == layout_width);
        if let Some(index) = index {
            let layout = self.layouts.remove(index).unwrap();
            self.layouts.push_back(layout);
        } else {
            let svg = self
                .renderer
                .render(source, layout_width)
                .map_err(|e| format!("Markdown 无法排版：{e}"))?;
            let tree = usvg::Tree::from_str(&svg, &usvg::Options::default())
                .map_err(|e| Failure::Internal(format!("SVG 无法解析：{e}")))?;
            // 两面共享固定容量；移除旧布局后再持有新布局，避免无限缓存。
            if self.layouts.len() == 2 {
                self.layouts.pop_front();
            }
            self.layouts.push_back(Layout {
                source: source.into(),
                width: layout_width,
                tree,
            });
        }
        let tree = &self.layouts.back().unwrap().tree;
        let mut pixmap = tiny_skia::Pixmap::new(width, height)
            .ok_or_else(|| Failure::Internal("无法分配 Markdown 图层".into()))?;
        let scale = width as f32 / layout_width as f32;
        let card_scale = width as f32 / 1024.0;
        let transform =
            tiny_skia::Transform::from_row(scale, 0.0, 0.0, scale, x * card_scale, y * card_scale);
        resvg::render(tree, transform, &mut pixmap.as_mut());
        // Android Bitmap.setPixels/createBitmap 接受非预乘 ARGB。
        Ok(pixmap
            .pixels()
            .iter()
            .map(|pixel| {
                let p = pixel.demultiply();
                ((p.alpha() as u32) << 24
                    | (p.red() as u32) << 16
                    | (p.green() as u32) << 8
                    | p.blue() as u32) as i32
            })
            .collect())
    }
}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_mutsumi_card_draw_Md2SvgNative_render(
    mut env: JNIEnv,
    _object: JObject,
    source: JString,
    layout_width: jint,
    width: jint,
    height: jint,
    x: jfloat,
    y: jfloat,
) -> jintArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<jintArray, Failure> {
        let source: String = env
            .get_string(&source)
            .map_err(|e| Failure::Internal(e.to_string()))?
            .into();
        let mut engine = ENGINE
            .get_or_init(|| Mutex::new(Engine::default()))
            .lock()
            .map_err(|_| Failure::Internal("Markdown 渲染器异常，请重启应用".into()))?;
        let pixels = engine.render(
            &source,
            layout_width as u32,
            width as u32,
            height as u32,
            x,
            y,
        )?;
        let array = env
            .new_int_array(pixels.len() as i32)
            .map_err(|e| Failure::Internal(e.to_string()))?;
        env.set_int_array_region(&array, 0, &pixels)
            .map_err(|e| Failure::Internal(e.to_string()))?;
        Ok(array.into_raw())
    }));
    match result {
        Ok(Ok(array)) => array,
        Ok(Err(failure)) => {
            let (class, message) = match failure {
                Failure::Input(message) => ("java/lang/IllegalArgumentException", message),
                Failure::Internal(message) => ("java/lang/IllegalStateException", message),
            };
            if !env.exception_check().unwrap_or(true) {
                env.throw_new(class, message).expect("无法传递 JNI 异常");
            }
            ptr::null_mut()
        }
        Err(_) => {
            env.throw_new(
                "java/lang/IllegalStateException",
                "Markdown 原生渲染器异常，请重启应用",
            )
            .expect("无法传递 JNI 异常");
            ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_chinese_math_table_and_mermaid_with_transparency() {
        let mut engine = Engine::default();
        let source = "# 中文标题\n\n$E=mc^2$\n\n|甲|乙|\n|---|---|\n|1|2|\n\n```mermaid\nflowchart LR\nA[开始]-->B[结束]\n```";
        let pixels = engine.render(source, 512, 256, 406, 0.0, 0.0).unwrap();
        assert_eq!(pixels.len(), 256 * 406);
        assert!(pixels.iter().any(|p| *p == 0));
        assert!(pixels.iter().filter(|p| **p != 0).count() > 100);
    }

    #[test]
    fn width_reflows_and_pan_reuses_layout() {
        let mut engine = Engine::default();
        let source = "# 测试\n\nMarkdown 排版宽度改变时，文字应重新换行。";
        let first = engine.render(source, 512, 256, 406, 0.0, 0.0).unwrap();
        let moved = engine.render(source, 512, 256, 406, 0.0, 400.0).unwrap();
        assert_eq!(engine.layouts.len(), 1);
        assert_ne!(first, moved);
        let zoomed = engine.render(source, 256, 256, 406, 0.0, 0.0).unwrap();
        assert_ne!(first, zoomed);
        assert_eq!(engine.layouts.len(), 2);
        engine.render(source, 1024, 256, 406, 0.0, 0.0).unwrap();
        assert_eq!(engine.layouts.len(), 2);
    }

    #[test]
    fn rejects_invalid_inputs_and_recovers() {
        let mut engine = Engine::default();
        assert!(engine.render("x", 0, 256, 406, 0.0, 0.0).is_err());
        assert!(
            engine
                .render("<script>x</script>", 512, 256, 406, 0.0, 0.0)
                .is_err()
        );
        assert!(engine.render("x", 512, 100_000, 100_000, 0.0, 0.0).is_err());
        assert!(engine.render("x", 512, 256, 406, f32::NAN, 0.0).is_err());
        assert!(engine.render("恢复正常", 512, 256, 406, 0.0, 0.0).is_ok());
    }
}
