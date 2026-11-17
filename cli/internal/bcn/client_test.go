package bcn

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// fakeAPI answers the login, refresh and post endpoints. The first post request with the old
// token gets 401, so the client must refresh once and retry.
func fakeAPI(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("POST /auth/otp/request", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["channel"] != "email" || body["address"] != "me@example.com" {
			t.Errorf("request body %v", body)
		}
		w.WriteHeader(http.StatusAccepted)
	})
	mux.HandleFunc("POST /auth/otp/verify", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["code"] != "123456" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "refresh_token", Value: "refresh-1", Path: "/"})
		_ = json.NewEncoder(w).Encode(map[string]any{"token": "old"})
	})
	mux.HandleFunc("POST /auth/refresh", func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie("refresh_token")
		if err != nil || cookie.Value != "refresh-1" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "refresh_token", Value: "refresh-2", Path: "/"})
		_ = json.NewEncoder(w).Encode(map[string]any{"token": "new"})
	})
	mux.HandleFunc("GET /posts/2026/11/hello", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer new" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"title": "Hello", "slug": "hello", "markdownSource": "Body.",
		})
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

func readSession(t *testing.T, path string) Session {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestLoginStoresSession(t *testing.T) {
	server := fakeAPI(t)
	path := filepath.Join(t.TempDir(), "bcn", "session.json")
	client := &Client{Server: server.URL, SessionPath: path}
	if err := client.RequestCode("me@example.com"); err != nil {
		t.Fatal(err)
	}
	if err := client.VerifyCode("me@example.com", "123456"); err != nil {
		t.Fatal(err)
	}
	want := Session{Server: server.URL, Token: "old", RefreshToken: "refresh-1"}
	if got := readSession(t, path); got != want {
		t.Errorf("session %+v, want %+v", got, want)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("session file mode %v, want 0600", info.Mode().Perm())
	}
}

func TestWrongCodeFails(t *testing.T) {
	server := fakeAPI(t)
	client := &Client{Server: server.URL, SessionPath: filepath.Join(t.TempDir(), "s.json")}
	if err := client.VerifyCode("me@example.com", "000000"); err == nil {
		t.Fatal("want an error for a wrong code")
	}
}

func TestExpiredTokenRefreshes(t *testing.T) {
	server := fakeAPI(t)
	path := filepath.Join(t.TempDir(), "session.json")
	client := &Client{Server: server.URL, SessionPath: path}
	if err := client.VerifyCode("me@example.com", "123456"); err != nil {
		t.Fatal(err)
	}
	post, err := client.GetPost(PostRef{2026, 11, "hello"})
	if err != nil {
		t.Fatal(err)
	}
	if post.Title != "Hello" || post.MarkdownSource == nil || *post.MarkdownSource != "Body." {
		t.Errorf("post %+v", post)
	}
	want := Session{Server: server.URL, Token: "new", RefreshToken: "refresh-2"}
	if got := readSession(t, path); got != want {
		t.Errorf("session %+v, want %+v", got, want)
	}
}

func TestNoSessionFails(t *testing.T) {
	server := fakeAPI(t)
	client := &Client{Server: server.URL, SessionPath: filepath.Join(t.TempDir(), "none.json")}
	if _, err := client.GetPost(PostRef{2026, 11, "hello"}); err == nil {
		t.Fatal("want an error before any login")
	}
}
