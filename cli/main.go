// Command bcn is a command-line client for the BCN API (bytecode.news): sign in, and write a
// post as a foojay.io draft. See README.md.
package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/streampack-dev/streampack/cli/internal/bcn"
)

const (
	defaultServer = "https://api.bytecode.news"
	defaultSite   = "https://bytecode.news"
)

const usage = `bcn: a command-line client for bytecode.news

Usage:
  bcn login [--server URL]
  bcn foojay [--out DIR] [--site URL] [--server URL] [--force] <post>

<post> is a post's URL, such as https://bytecode.news/posts/2026/11/slug, or 2026/11/slug.
`

func main() {
	if err := run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr); err != nil {
		fmt.Fprintln(os.Stderr, "bcn:", err)
		os.Exit(1)
	}
}

func run(args []string, in io.Reader, out, errOut io.Writer) error {
	if len(args) == 0 {
		fmt.Fprint(errOut, usage)
		return errors.New("no command")
	}
	switch args[0] {
	case "login":
		return login(args[1:], in, out)
	case "foojay":
		return foojay(args[1:], out, errOut)
	case "help", "-h", "--help":
		fmt.Fprint(out, usage)
		return nil
	}
	fmt.Fprint(errOut, usage)
	return fmt.Errorf("unknown command %q", args[0])
}

// sessionPath is bcn/session.json in the user's configuration directory: on macOS
// ~/Library/Application Support, on Linux $XDG_CONFIG_HOME or ~/.config.
func sessionPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "bcn", "session.json"), nil
}

func client(server string) (*bcn.Client, error) {
	path, err := sessionPath()
	if err != nil {
		return nil, err
	}
	return &bcn.Client{Server: strings.TrimRight(server, "/"), SessionPath: path}, nil
}

func login(args []string, in io.Reader, out io.Writer) error {
	flags := flag.NewFlagSet("login", flag.ContinueOnError)
	server := flags.String("server", defaultServer, "the API's base URL")
	if err := flags.Parse(args); err != nil {
		return err
	}
	c, err := client(*server)
	if err != nil {
		return err
	}
	reader := bufio.NewReader(in)
	email, err := prompt(reader, out, "Email: ")
	if err != nil {
		return err
	}
	if err := c.RequestCode(email); err != nil {
		return err
	}
	code, err := prompt(reader, out, "Code from the email: ")
	if err != nil {
		return err
	}
	if err := c.VerifyCode(email, code); err != nil {
		return err
	}
	fmt.Fprintf(out, "Signed in to %s; session in %s\n", c.Server, c.SessionPath)
	return nil
}

func prompt(reader *bufio.Reader, out io.Writer, label string) (string, error) {
	fmt.Fprint(out, label)
	line, err := reader.ReadString('\n')
	line = strings.TrimSpace(line)
	if line == "" {
		if err == nil {
			err = errors.New("nothing entered")
		}
		return "", err
	}
	return line, nil
}

func foojay(args []string, out, errOut io.Writer) error {
	flags := flag.NewFlagSet("foojay", flag.ContinueOnError)
	dir := flags.String("out", ".", "the foojay checkout; the draft goes in its draft/ directory")
	site := flags.String("site", defaultSite, "the site's URL, for canonical and absolute links")
	server := flags.String("server", defaultServer, "the API's base URL")
	force := flags.Bool("force", false, "replace an existing index.md")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 1 {
		return errors.New("give one post: its URL or year/month/slug")
	}
	ref, err := bcn.ParsePostRef(flags.Arg(0))
	if err != nil {
		return err
	}
	c, err := client(*server)
	if err != nil {
		return err
	}
	post, err := c.GetPost(ref)
	if err != nil {
		return err
	}
	folder, content, warnings, err := bcn.FoojayDraft(ref, post, *site)
	if err != nil {
		return err
	}
	path := filepath.Join(*dir, "draft", folder, "index.md")
	if _, err := os.Stat(path); err == nil && !*force {
		return fmt.Errorf("%s exists; add --force to replace it", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		return err
	}
	fmt.Fprintln(out, "Wrote", path)
	for _, w := range warnings {
		fmt.Fprintln(errOut, "warning:", w)
	}
	fmt.Fprintln(errOut, "To do: the author slug, the categories, and the image, with its file in the folder.")
	return nil
}
