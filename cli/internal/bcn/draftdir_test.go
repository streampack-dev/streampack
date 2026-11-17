package bcn

import (
	"os"
	"path/filepath"
	"testing"
)

// The directory a draft folder goes in: a foojay checkout's draft/, or the given directory.
func TestDraftDir(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "draft"), 0o755); err != nil {
		t.Fatal(err)
	}
	other := t.TempDir()
	cases := []struct{ name, dir, want string }{
		{"foojay checkout uses its draft directory", root, filepath.Join(root, "draft")},
		{"draft directory is used as it is", filepath.Join(root, "draft"), filepath.Join(root, "draft")},
		{"any other directory is used as it is", other, other},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := DraftDir(c.dir); got != c.want {
				t.Errorf("got %s, want %s", got, c.want)
			}
		})
	}
}
