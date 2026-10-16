# Alibaba Cloud icon sources

The parent plugin icon and OSS subgroup icon come from upstream commit `fbbad5a` (PR #13), where maintainers selected the Alibaba logo. This contribution preserves those upstream assets.

The MaxCompute, SLS and Function Compute subgroup icons use original glyph outlines from Alibaba Cloud's own product icon font, linked by its public product pages:

- Site: https://www.alibabacloud.com/en/product/log-service
- Stylesheet: https://at.alicdn.com/t/font_274588_3h2v001vxq2.css
- SVG font: https://at.alicdn.com/t/font_274588_3h2v001vxq2.svg

| Plugin asset | Official glyph | Code point |
| --- | --- | --- |
| `io.kestra.plugin.alibaba.maxcompute.svg` | `odps-data` | U+E941 |
| `io.kestra.plugin.alibaba.sls.svg` | `sls` | U+E72A |
| `io.kestra.plugin.alibaba.fc.svg` | `fc` | U+E60C |

The official product page identifies MaxCompute with the `dbl-icon-product-odps-data` class. The stylesheet maps the class to the corresponding glyph; the SLS class maps to `sls`, and the Function Compute class `dbl-icon-product-fc` to `fc`.

Each SVG preserves the complete original glyph path and the font's 1024-unit canvas. The transform converts the font's upward Y axis and 896-unit ascent to the SVG coordinate system. The orange fill is Alibaba Cloud's site brand color. No path outlines were drawn or approximated. Alibaba Cloud retains ownership of its marks.

The product glyphs are public artwork, but this source record does not establish a redistribution license. Their licensing acceptance remains a maintainer decision.
