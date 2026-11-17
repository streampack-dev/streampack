package bcn

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

// refreshCookie is the cookie the API sets the refresh token in, and reads it from: the login
// answer's JSON leaves the refresh token out.
const refreshCookie = "refresh_token"

// Session is the stored login: the API it is for, the access token and the refresh token. The
// API rotates the refresh token on each refresh, so the file is rewritten each time.
type Session struct {
	Server       string `json:"server"`
	Token        string `json:"token"`
	RefreshToken string `json:"refreshToken"`
}

// Client calls the BCN API at Server, with the session stored at SessionPath.
type Client struct {
	// Server is the API's base URL, such as https://api.bytecode.news, without a trailing slash.
	Server string
	// SessionPath is the session file; it is created with mode 0600, its directory with 0700.
	SessionPath string
	// HTTP is the HTTP client; nil means http.DefaultClient.
	HTTP *http.Client
}

// RequestCode asks the API to email a sign-in code to email. The API answers the same whether
// or not the address has an account.
func (c *Client) RequestCode(email string) error {
	resp, err := c.post("/auth/otp/request", map[string]string{"channel": "email", "address": email}, "")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return expect(resp, "request a code")
}

// VerifyCode exchanges the emailed code for a session and stores it.
func (c *Client) VerifyCode(email, code string) error {
	resp, err := c.post("/auth/otp/verify",
		map[string]string{"channel": "email", "address": email, "code": code}, "")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if err := expect(resp, "sign in"); err != nil {
		return err
	}
	return c.store(resp)
}

// GetPost reads a post with the stored session. An expired access token is refreshed once.
func (c *Client) GetPost(ref PostRef) (Post, error) {
	path := fmt.Sprintf("/posts/%d/%02d/%s", ref.Year, ref.Month, ref.Slug)
	resp, err := c.authorized(http.MethodGet, path)
	if err != nil {
		return Post{}, err
	}
	defer resp.Body.Close()
	if err := expect(resp, "read "+path); err != nil {
		return Post{}, err
	}
	var post Post
	if err := json.NewDecoder(resp.Body).Decode(&post); err != nil {
		return Post{}, fmt.Errorf("read %s: %w", path, err)
	}
	return post, nil
}

// authorized sends a request with the session's token, and on 401 refreshes and sends it again.
func (c *Client) authorized(method, path string) (*http.Response, error) {
	session, err := c.load()
	if err != nil {
		return nil, err
	}
	resp, err := c.send(method, path, session.Token)
	if err != nil || resp.StatusCode != http.StatusUnauthorized {
		return resp, err
	}
	resp.Body.Close()
	if err := c.refresh(session); err != nil {
		return nil, err
	}
	session, err = c.load()
	if err != nil {
		return nil, err
	}
	return c.send(method, path, session.Token)
}

// refresh trades the refresh token, sent as its cookie, for new tokens, and stores them.
func (c *Client) refresh(session Session) error {
	resp, err := c.post("/auth/refresh", nil, session.RefreshToken)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusUnauthorized {
		return errors.New("the session has expired; sign in again with: bcn login")
	}
	if err := expect(resp, "refresh the session"); err != nil {
		return err
	}
	return c.store(resp)
}

func (c *Client) send(method, path, token string) (*http.Response, error) {
	req, err := http.NewRequest(method, c.Server+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/json")
	return c.client().Do(req)
}

func (c *Client) post(path string, body any, refreshToken string) (*http.Response, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest(http.MethodPost, c.Server+path, bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if refreshToken != "" {
		req.AddCookie(&http.Cookie{Name: refreshCookie, Value: refreshToken})
	}
	return c.client().Do(req)
}

func (c *Client) client() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return http.DefaultClient
}

// store saves the token from a login or refresh answer and the refresh token from its cookie.
func (c *Client) store(resp *http.Response) error {
	var body struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil || body.Token == "" {
		return errors.New("the API's answer held no token")
	}
	session := Session{Server: c.Server, Token: body.Token}
	for _, cookie := range resp.Cookies() {
		if cookie.Name == refreshCookie {
			session.RefreshToken = cookie.Value
		}
	}
	if session.RefreshToken == "" {
		return errors.New("the API's answer held no refresh token")
	}
	return c.save(session)
}

func (c *Client) load() (Session, error) {
	data, err := os.ReadFile(c.SessionPath)
	if errors.Is(err, os.ErrNotExist) {
		return Session{}, errors.New("not signed in; sign in with: bcn login")
	}
	if err != nil {
		return Session{}, err
	}
	var session Session
	if err := json.Unmarshal(data, &session); err != nil {
		return Session{}, fmt.Errorf("%s is not a session file: %w", c.SessionPath, err)
	}
	if session.Server != c.Server {
		return Session{}, fmt.Errorf("the session is for %s, not %s; sign in with: bcn login",
			session.Server, c.Server)
	}
	return session, nil
}

func (c *Client) save(session Session) error {
	if err := os.MkdirAll(filepath.Dir(c.SessionPath), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(session, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(c.SessionPath, data, 0o600); err != nil {
		return err
	}
	// WriteFile keeps an existing file's mode; the file holds tokens.
	return os.Chmod(c.SessionPath, 0o600)
}

// expect returns an error naming what failed, the status and the API's message, unless resp is
// a success.
func expect(resp *http.Response, what string) error {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
	message := strings.TrimSpace(string(body))
	var parsed struct {
		Message string `json:"message"`
		Error   string `json:"error"`
	}
	if json.Unmarshal(body, &parsed) == nil {
		if parsed.Message != "" {
			message = parsed.Message
		} else if parsed.Error != "" {
			message = parsed.Error
		}
	}
	return fmt.Errorf("could not %s: %s %s", what, resp.Status, message)
}
