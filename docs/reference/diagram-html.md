# Diagram HTML

A post's or comment's ```` ```mermaid ```` block is drawn when it's saved (#252), with [Pele](https://pele.run) 1.6, and stored in `renderedHtml`. This page describes that HTML, for front ends that show it. Authors' instructions are in [Draw Diagrams in Posts](../how-to/blog/draw-diagrams-in-posts.md).

## A Drawn Diagram

```html
<figure class="mermaid">
  <svg class="pele pele-flowchart" role="graphics-document document"
       aria-roledescription="flowchart" aria-label="Diagram (flowchart)" viewBox="…">…</svg>
  <details>
    <summary>Diagram source</summary>
    <pre><code class="language-mermaid">flowchart LR
  A --&gt; B
</code></pre>
  </details>
</figure>
```

- The SVG scales to its container (`max-width:100%;height:auto`).
- With the author's `accTitle`, the SVG has `aria-labelledby` naming its `<title>` instead of `aria-label`, and `accDescr` gives it `aria-describedby` and a `<desc>`.
- Element ids start with `mermaid-<hash>-<n>`, so diagrams on one page don't clash.
- Labels were measured in IBM Plex Sans and IBM Plex Mono, and the SVG names them: `font-family:var(--pele-font,"IBM Plex Sans", sans-serif)`. See [Fonts](#fonts).

## A Refused Diagram

```html
<figure class="mermaid mermaid-refused">
  <pre><code class="language-mermaid">flowchart LR
  A --&gt;
</code></pre>
  <figcaption>This diagram wasn't drawn: Parse error on line 3: Expecting 'NODE_STRING', got 'EOF'</figcaption>
</figure>
```

A block that wasn't attempted (diagrams turned off, or after the fiftieth in one post) stays as flexmark writes any fenced block: `<pre><code class="language-mermaid">`.

## Theme Variables

The SVG has no fixed colours of its own. It reads these CSS custom properties, with light defaults when they're unset. Set them on `figure.mermaid`, or any ancestor, for each theme:

| Variable | Default | Used for |
|---|---|---|
| `--pele-bg` | `#fff` | The background |
| `--pele-fg` | `#1f1f1f` | Text |
| `--pele-muted` | `#6e6e6e` | Secondary text |
| `--pele-line` | `#1f1f1f` | Edges and arrows |
| `--pele-surface` | `#f3f3f3` | Node fill |
| `--pele-surface-alt` | `#e6e6e6` | Alternate fill, such as groups |
| `--pele-border` | `#8a8a8a` | Node borders |
| `--pele-accent` | `#5b7bd5` | Highlights |
| `--pele-font` | `"IBM Plex Sans", sans-serif` | Label font. Don't set it: see [Fonts](#fonts). |
| `--pele-font-mono` | `"IBM Plex Mono", monospace` | Monospace labels. Don't set it either. |
| `--pele-radius` | `4px` | Corner radius |
| `--pele-series-1` … `--pele-series-8` | Pele's palette | Chart series |

Changing them takes effect without drawing again. Colours an author sets with `style` or `classDef` are written into the SVG and don't follow the theme.

## Fonts

Each node's box is sized to its label when the diagram is drawn, so the label has to be shown in the font it was measured in. The server measures in IBM Plex Sans and IBM Plex Mono (SIL Open Font License), which it bundles, and the SVG names them. A front end that shows diagrams:

- serves IBM Plex Sans and IBM Plex Mono, regular, bold, italic and bold italic, for `figure.mermaid`;
- doesn't set `--pele-font` or `--pele-font-mono`.

Without the fonts, the browser falls back to `sans-serif`, and labels may be wider or narrower than their boxes.

Characters Plex doesn't have are measured in the server's fallback font, and characters no font on the server has are estimated (an em for CJK). The browser draws those in a font of its own, so their boxes are close, not exact.

## What the Sanitizer Keeps

[`RenderedHtmlSanitizer`](../../lib-blog/src/main/kotlin/dev/streampack/blog/service/RenderedHtmlSanitizer.kt) rebuilds every `figure.mermaid` from its parts, when a post is saved and when stored HTML is sanitized again at startup:

- The SVG keeps only shapes (`g`, `rect`, `circle`, `ellipse`, `line`, `polyline`, `polygon`, `path`), text (`text`, `tspan`, `title`, `desc`), and their geometry and presentation attributes.
- Inline `style` keeps only presentation properties and custom properties. No attribute may hold `url(`, a script scheme, or an escape.
- There are no links, images or `<use>` references. Pele is called with links and images turned off.
- The source and the caption are text.

Anything else in a stored figure is dropped. A figure with no source is removed.
