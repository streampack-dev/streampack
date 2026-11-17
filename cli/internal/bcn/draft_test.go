package bcn

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

const site = "https://bytecode.news"

// load reads a JSON case file from testdata into v.
func load(t *testing.T, name string, v any) {
	t.Helper()
	data, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, v); err != nil {
		t.Fatal(err)
	}
}

func TestTransformBody(t *testing.T) {
	var cases []struct{ Name, Input, Expected string }
	load(t, "transform.json", &cases)
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			if got := TransformBody(c.Input, site); got != c.Expected {
				t.Errorf("got %q, want %q", got, c.Expected)
			}
		})
	}
}

func TestParsePostRef(t *testing.T) {
	var cases []struct {
		Arg         string
		Year, Month int
		Slug        string
		Error       bool
	}
	load(t, "postref.json", &cases)
	for _, c := range cases {
		t.Run(c.Arg, func(t *testing.T) {
			ref, err := ParsePostRef(c.Arg)
			if c.Error {
				if err == nil {
					t.Errorf("want an error, got %+v", ref)
				}
				return
			}
			want := PostRef{c.Year, c.Month, c.Slug}
			if err != nil || ref != want {
				t.Errorf("got %+v, %v; want %+v", ref, err, want)
			}
		})
	}
}

func TestFoojayDraft(t *testing.T) {
	var cases []struct {
		Name     string
		Ref      PostRef
		Post     Post
		Folder   string
		Content  string
		Warnings []string
	}
	load(t, "draft.json", &cases)
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			folder, content, warnings, err := FoojayDraft(c.Ref, c.Post, site)
			if err != nil {
				t.Fatal(err)
			}
			if folder != c.Folder {
				t.Errorf("folder %q, want %q", folder, c.Folder)
			}
			if content != c.Content {
				t.Errorf("content:\n%s\nwant:\n%s", content, c.Content)
			}
			if len(warnings) != 0 || len(c.Warnings) != 0 {
				if !reflect.DeepEqual(warnings, c.Warnings) {
					t.Errorf("warnings %q, want %q", warnings, c.Warnings)
				}
			}
		})
	}
}

func TestFoojayDraftWithoutSource(t *testing.T) {
	_, _, _, err := FoojayDraft(PostRef{2026, 1, "x"}, Post{Title: "X", Slug: "x"}, site)
	if err == nil {
		t.Fatal("want an error when the API gave no Markdown source")
	}
}
