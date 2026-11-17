package bcn

import (
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

// The placeholders foojay's own template uses. foojay's pull-request check rejects all three,
// so a draft that still carries one cannot be merged by mistake.
const (
	authorPlaceholder = "your-author-slug"
	// maxDescription is foojay's limit: a description "below 160 characters".
	maxDescription = 160
)

var (
	// A factoid link, text first as BCN's renderer reads it: [[selector]] or [[text|selector]].
	factoidLink = regexp.MustCompile(`\[\[([^\]|]+)(?:\|[^\]]*)?\]\]`)
	// A Markdown link or image whose target is a path on the site, such as ](/posts/...).
	siteRelative = regexp.MustCompile(`\]\((/[^)\s]*)`)
	fence        = regexp.MustCompile("^ {0,3}(```|~~~)")
	// A flexmark admonition's first line: !!! kind "title", or ??? and ???+ for a collapsible one.
	admonition = regexp.MustCompile(`^(?:!!!|\?\?\?\+?) +([A-Za-z][\w-]*)(?: +"([^"]*)")? *$`)
)

// foojayKinds maps flexmark's admonition kinds to foojay's five shortcodes, by meaning. Any
// other kind becomes a note.
var foojayKinds = map[string]string{
	"tip": "tip", "hint": "tip", "success": "tip", "check": "tip", "done": "tip",
	"important": "important",
	"warning":   "warning", "attention": "warning", "failure": "warning", "fail": "warning",
	"missing": "warning", "bug": "warning",
	"caution": "caution", "danger": "caution", "error": "caution",
}

// TransformBody turns a BCN post's Markdown into foojay's:
//   - a leading "# " title line is dropped, because foojay renders the title as the page's H1;
//   - factoid links become their plain text, because foojay has no factoids;
//   - links and images to a site path become absolute URLs on site;
//   - admonitions become foojay's shortcodes (see unfoldAdmonitions).
//
// Code fences and inline code are left as they are. Footnotes and mermaid blocks work on both
// sites and are not touched.
func TransformBody(markdown, site string) string {
	site = strings.TrimRight(site, "/")
	lines := unfoldAdmonitions(strings.Split(markdown, "\n"))
	var out []string
	inFence, started := false, false
	for _, line := range lines {
		if !started {
			if strings.TrimSpace(line) == "" {
				continue
			}
			started = true
			if strings.HasPrefix(line, "# ") {
				continue
			}
		}
		if fence.MatchString(line) {
			inFence = !inFence
			out = append(out, line)
			continue
		}
		if inFence {
			out = append(out, line)
			continue
		}
		out = append(out, transformLine(line, site))
	}
	// Blank lines between a dropped title and the first paragraph go too.
	for len(out) > 0 && strings.TrimSpace(out[0]) == "" {
		out = out[1:]
	}
	return strings.Join(out, "\n")
}

// unfoldAdmonitions turns each flexmark admonition outside a code fence into a foojay shortcode
// pair, {{< kind >}} and {{< /kind >}}, around its body with one level of indent removed. foojay
// has only note, tip, important, warning and caution (see foojayKinds), shows no title and
// cannot collapse; so a title becomes a bold first line, and a collapsible block is shown open.
// The body ends at the first line that is not blank and not indented, as in flexmark.
func unfoldAdmonitions(lines []string) []string {
	var out []string
	inFence := false
	for i := 0; i < len(lines); i++ {
		line := lines[i]
		if fence.MatchString(line) {
			inFence = !inFence
		}
		m := admonition.FindStringSubmatch(line)
		if inFence || m == nil {
			out = append(out, line)
			continue
		}
		var body []string
		for i+1 < len(lines) {
			next := lines[i+1]
			if strings.TrimSpace(next) == "" {
				body = append(body, "")
			} else if strings.HasPrefix(next, "    ") {
				body = append(body, next[4:])
			} else if strings.HasPrefix(next, "\t") {
				body = append(body, next[1:])
			} else {
				break
			}
			i++
		}
		trailing := 0
		for len(body) > 0 && body[len(body)-1] == "" {
			body = body[:len(body)-1]
			trailing++
		}
		for len(body) > 0 && body[0] == "" {
			body = body[1:]
		}
		kind, ok := foojayKinds[strings.ToLower(m[1])]
		if !ok {
			kind = "note"
		}
		out = append(out, "{{< "+kind+" >}}")
		if m[2] != "" {
			out = append(out, "**"+m[2]+"**", "")
		}
		out = append(out, unfoldAdmonitions(body)...)
		out = append(out, "{{< /"+kind+" >}}")
		for ; trailing > 0; trailing-- {
			out = append(out, "")
		}
	}
	return out
}

// transformLine changes the parts of a line outside inline code spans.
func transformLine(line, site string) string {
	parts := strings.Split(line, "`")
	for i := 0; i < len(parts); i += 2 {
		p := factoidLink.ReplaceAllString(parts[i], "$1")
		parts[i] = siteRelative.ReplaceAllString(p, "]("+site+"$1")
	}
	return strings.Join(parts, "`")
}

// FoojayDraft builds a foojay.io draft from a post: the folder name (the last part of the post's
// slug with a "bcn-" prefix, so foojay's slugs show where the article came from) and the content of its
// index.md. The author, the categories and the image are left as foojay's placeholders for the
// user to fill in; the post's BCN tags are listed as a hint. Warnings name problems foojay's
// check would report. It returns an error when post has no Markdown source, which the API gives
// only to admins.
func FoojayDraft(ref PostRef, post Post, site string) (folder, content string, warnings []string, err error) {
	if post.MarkdownSource == nil {
		return "", "", nil, errors.New("the API gave no Markdown source for this post; " +
			"only an admin receives it, so sign in as one with: bcn login")
	}
	site = strings.TrimRight(site, "/")
	// The API's slug is the post's path, such as 2026/11/name; ref may name an alias of it.
	postPath := post.Slug
	if !strings.Contains(postPath, "/") {
		name := postPath
		if name == "" {
			name = ref.Slug
		}
		postPath = fmt.Sprintf("%04d/%02d/%s", ref.Year, ref.Month, name)
	}
	description := strings.TrimSpace(post.Excerpt)
	switch n := utf8.RuneCountInString(description); {
	case n == 0:
		warnings = append(warnings, "description is empty; foojay requires one")
	case n >= maxDescription:
		warnings = append(warnings, fmt.Sprintf(
			"description is %d characters; foojay wants fewer than %d", n, maxDescription))
	}
	hints := "none"
	if names := tagNames(post); len(names) > 0 {
		hints = strings.Join(names, ", ")
	}
	var b strings.Builder
	b.WriteString("---\n")
	fmt.Fprintf(&b, "title: %s\n", quote(post.Title))
	fmt.Fprintf(&b, "description: %s\n", quote(description))
	fmt.Fprintf(&b, "authors:\n  - %s\n", quote(authorPlaceholder))
	fmt.Fprintf(&b, "# BCN tags: %s\n", hints)
	b.WriteString("categories: []\n")
	b.WriteString("image: \"\"\n")
	fmt.Fprintf(&b, "canonical: %s\n",
		quote(site+"/posts/"+postPath))
	b.WriteString("---\n\n")
	b.WriteString(strings.TrimRight(TransformBody(*post.MarkdownSource, site), "\n"))
	b.WriteString("\n")
	return "bcn-" + path.Base(postPath), b.String(), warnings, nil
}

// tagNames is the post's tags, then any category not already among them.
func tagNames(post Post) []string {
	seen := map[string]bool{}
	var names []string
	for _, n := range append(append([]string{}, post.Tags...), post.Categories...) {
		if !seen[n] {
			seen[n] = true
			names = append(names, n)
		}
	}
	return names
}

// quote writes s as a double-quoted YAML string.
func quote(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`)
	return `"` + r.Replace(s) + `"`
}

// DraftDir is the directory a draft folder goes in. A foojay checkout, known by its draft/
// subdirectory, gives that draft/; any other directory, draft/ itself included, is used as it
// is. The draft folder then goes one level inside: <DraftDir>/bcn-<slug>/index.md.
func DraftDir(dir string) string {
	draft := filepath.Join(dir, "draft")
	if info, err := os.Stat(draft); err == nil && info.IsDir() {
		return draft
	}
	return dir
}
