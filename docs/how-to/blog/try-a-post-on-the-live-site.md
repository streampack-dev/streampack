# Try a Post on the Live Site

To see how a post looks on the real site before readers do (a diagram, a recording, a layout), put it in a category whose name starts with `_`, such as `_test`. The post is published and readable at its own address on every front end, and it goes nowhere else. See [System Posts](../../reference/blog-http-api.md#system-posts) for what that leaves out.

1. Once, make the category. An admin adds `_test` in the site's category admin, or with `POST /admin/categories` and `{"name": "_test"}`.
2. Write the post in `_test`, and publish it. Made in a `_` category, it gets an undated slug from its title: "Diagram check" is at `/posts/diagram-check`.
3. Open that address on each site you want to check.

The post isn't in the board, lists, Popular Reads, tag pages, search or any feed, and its links aren't sent Webmentions or followed for feeds. It is in `/sitemap.xml`, so a search engine may still find it.

When you're done, delete the post. A test post isn't meant to become a real one: write the real post afresh, so it gets a dated address and today's date.
