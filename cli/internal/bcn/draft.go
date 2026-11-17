package bcn

import (
	"errors"
	"fmt"
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
)

// TransformBody turns a BCN post's Markdown into foojay's:
//   - a leading "# " title line is dropped, because foojay renders the title as the page's H1;
//   - factoid links become their plain text, because foojay has no factoids;
//   - links and images to a site path become absolute URLs on site.
//
// Code fences and inline code are left as they are. Footnotes and mermaid blocks work on both
// sites and are not touched.
func TransformBody(markdown, site string) string {
	site = strings.TrimRight(site, "/")
	lines := strings.Split(markdown, "\n")
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

// transformLine changes the parts of a line outside inline code spans.
func transformLine(line, site string) string {
	parts := strings.Split(line, "`")
	for i := 0; i < len(parts); i += 2 {
		p := factoidLink.ReplaceAllString(parts[i], "$1")
		parts[i] = siteRelative.ReplaceAllString(p, "]("+site+"$1")
	}
	return strings.Join(parts, "`")
}

// FoojayDraft builds a foojay.io draft from a post: the folder name (the post's slug with a
// "bcn-" prefix, so foojay's slugs show where the article came from) and the content of its
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
	slug := post.Slug
	if slug == "" {
		slug = ref.Slug
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
		quote(fmt.Sprintf("%s/posts/%04d/%02d/%s", site, ref.Year, ref.Month, slug)))
	b.WriteString("---\n\n")
	b.WriteString(strings.TrimRight(TransformBody(*post.MarkdownSource, site), "\n"))
	b.WriteString("\n")
	return "bcn-" + slug, b.String(), warnings, nil
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
