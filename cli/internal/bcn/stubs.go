// Package bcn talks to the BCN API and turns its posts into foojay.io drafts.
package bcn

import "errors"

// errNotImplemented marks the functions the red tests call before they exist.
var errNotImplemented = errors.New("not implemented")

// Post is a post as GET /posts/{year}/{month}/{slug} returns it.
type Post struct {
	Title          string   `json:"title"`
	Slug           string   `json:"slug"`
	Excerpt        string   `json:"excerpt"`
	Tags           []string `json:"tags"`
	Categories     []string `json:"categories"`
	MarkdownSource *string  `json:"markdownSource"`
}

// PostRef names a post by its address on the site.
type PostRef struct {
	Year  int
	Month int
	Slug  string
}

// ParsePostRef is not implemented yet.
func ParsePostRef(arg string) (PostRef, error) { return PostRef{}, errNotImplemented }

// TransformBody is not implemented yet.
func TransformBody(markdown, site string) string { return "" }

// FoojayDraft is not implemented yet.
func FoojayDraft(ref PostRef, post Post, site string) (folder, content string, warnings []string, err error) {
	return "", "", nil, errNotImplemented
}

// Session is the stored login.
type Session struct {
	Server       string `json:"server"`
	Token        string `json:"token"`
	RefreshToken string `json:"refreshToken"`
}

// Client calls the API with a stored session.
type Client struct {
	Server      string
	SessionPath string
}

// RequestCode is not implemented yet.
func (c *Client) RequestCode(email string) error { return errNotImplemented }

// VerifyCode is not implemented yet.
func (c *Client) VerifyCode(email, code string) error { return errNotImplemented }

// GetPost is not implemented yet.
func (c *Client) GetPost(ref PostRef) (Post, error) { return Post{}, errNotImplemented }
