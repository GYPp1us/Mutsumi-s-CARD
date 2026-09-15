# Pinned sources and local modifications

The patched dependencies are maintained in GYPp1us forks and referenced by
immutable commit in `Cargo.toml`, so a fresh clone does not depend on sibling
working directories. Their upstream notices and licenses remain applicable.

The original md2svg code is licensed under MIT. Preserve every upstream
license/notice for bundled assets and transitive dependencies when distributing
it; an MIT license for this project does not relicense Apache-2.0 or OFL files.

| Component | Source/version | Local changes |
|---|---|---|
| Typst, typst-layout, typst-svg, typst-assets | 0.15.1; Apache-2.0 for code | None. Embedded font licenses are separate; see licenses/typst-assets-NOTICE. |
| MiTeX | [GYPp1us/mitex](https://github.com/GYPp1us/mitex/tree/md2svg-v0.1.0) commit `5709621a762d3828a2357a3f479e3bb83e4223d3` (based on upstream `985d8e7`); Apache-2.0 | Parser/converter use the same supplied spec; cases column spacing; regression tests. |
| MiTeX artifacts | https://github.com/mitex-rs/artifacts commit 9eb762afa001b36205408c7615a73e5dfaa6f80a | Native build uses prebuilt artifact, but runtime conversion explicitly uses our checked-in regenerated JSON table. |
| mermaid-rs-renderer | [GYPp1us/mermaid-rs-renderer](https://github.com/GYPp1us/mermaid-rs-renderer/tree/md2svg-v0.1.0) commit `effd13cc09b7d44c8b2c19d733d747b37848010e` (based on upstream `3726ccb`); MIT | Guarded clear-straight-route early return; immutable shared font registration; bounded glyph/advance maps. |
| Noto Sans CJK SC Regular / Bold | https://github.com/notofonts/noto-cjk commit f8d157532fbfaeda587e826d4cd5b21a49186f7c | Unmodified OTF files from Sans/OTF/SimplifiedChinese. OFL license copied to assets/fonts/OFL.txt. |

Cargo.lock records the remaining Rust dependency versions. Windows SDK declarations are used only by the measurement example.

Noto SHA-256:

- Regular: `2C76254F6FC379FDDFCE0A7E84FB5385BB135D3E399294F6EEB6680D0365B74B`
- Bold: `B5F0D1A190A7F9B43C310A8850630AF12553DF32C4C050543F9059732D9B4C0A`

The three files in assets/mitex/specs originate from the pinned MiTeX packages/mitex/specs tree. Local edits to latex/standard.typ fix Vmatrix, smallmatrix, basic array alignment and vertical rules. They retain the upstream Apache-2.0 license; local changes are explicitly identified here. commands.json was generated from that pinned native scope's metadata, not from the stale default WASM artifact. These scope-only fixes do not change command signatures.

Typst's bundled fonts include Libertinus, DejaVu and New Computer Modern, under different font licenses. The complete upstream typst-assets NOTICE is included; do not describe all of them as OFL. No Windows proprietary fonts are bundled or loaded by this SDK.

The saved patches are generated against the commits above; they have not been
submitted upstream. The main repository uses the fork commits above rather
than silently depending on mutable branches. Complete transitive notice review
is still required before a crates.io publication.

`patches/mermaid-cross-tests.patch` additionally lets the invariant test harness read
`MMDR_TEST_FIXTURE_ROOT` when running cross-built tests on Linux. It changes only
fixture location; the fixtures and test assertions are unchanged. This variable
is not part of the rendering SDK or its public configuration.
