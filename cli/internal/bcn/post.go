// Package bcn talks to the BCN API and turns its posts into foojay.io drafts.
package bcn

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// Post is a post as GET /posts/{year}/{month}/{slug} returns it. Only the fields the export
// uses are read.
type Post struct {
	Title      string   `json:"title"`
	Slug       string   `json:"slug"`
	Excerpt    string   `json:"excerpt"`
	Tags       []string `json:"tags"`
	Categories []string `json:"categories"`
	// MarkdownSource is null unless the caller is an admin; the export needs it.
	MarkdownSource *string `json:"markdownSource"`
}

// PostRef names a post by its address on the site: /posts/{Year}/{Month}/{Slug}.
type PostRef struct {
	Year  int
	Month int
	Slug  string
}

// ParsePostRef reads a post's site URL (https://bytecode.news/posts/2026/11/slug) or the short
// form 2026/11/slug. A trailing slash is allowed. It returns an error for any other shape, or a
// month outside 1 to 12.
func ParsePostRef(arg string) (PostRef, error) {
	path := strings.TrimSpace(arg)
	if strings.Contains(path, "://") {
		u, err := url.Parse(path)
		if err != nil {
			return PostRef{}, fmt.Errorf("%q is not a URL: %w", arg, err)
		}
		if !strings.HasPrefix(u.Path, "/posts/") {
			return PostRef{}, fmt.Errorf("%q is not a post URL: the path must start with /posts/", arg)
		}
		path = strings.TrimPrefix(u.Path, "/posts/")
	}
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) != 3 || parts[2] == "" {
		return PostRef{}, fmt.Errorf("%q is not a post: give its URL or year/month/slug", arg)
	}
	year, err := strconv.Atoi(parts[0])
	if err != nil || year < 1000 || year > 9999 {
		return PostRef{}, fmt.Errorf("%q: %q is not a year", arg, parts[0])
	}
	month, err := strconv.Atoi(parts[1])
	if err != nil || month < 1 || month > 12 {
		return PostRef{}, fmt.Errorf("%q: %q is not a month", arg, parts[1])
	}
	return PostRef{Year: year, Month: month, Slug: parts[2]}, nil
}
