/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import com.rometools.rome.io.SyndFeedOutput
import dev.streampack.blog.service.RssFeedService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Serves the blog RSS or Atom feed based on request URL */
@RestController
class RssFeedController(private val rssFeedService: RssFeedService) {

    @GetMapping("/blog/rss.xml")
    fun rssFeed(): ResponseEntity<String> {
        val feed = rssFeedService.buildFeed()
        feed.feedType = "rss_2.0"
        val xml = SyndFeedOutput().outputString(feed)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/rss+xml; charset=utf-8"))
            .body(xml)
    }

    @GetMapping("/blog/atom.xml")
    fun atomFeed(): ResponseEntity<String> {
        val feed = rssFeedService.buildFeed()
        feed.feedType = "atom_1.0"
        val xml = SyndFeedOutput().outputString(feed)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/atom+xml; charset=utf-8"))
            .body(xml)
    }
}
