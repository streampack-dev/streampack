# service-moderation

`service-moderation` is the admin HTTP API over `operation-moderation` (#150), for PUDL's
Moderation window: list abuse reports, open one with its excerpt (hidden lines included and
marked), hide, unhide or purge lines, dismiss a report, and read a channel's log day with its
hidden lines. Every endpoint is for admins only, and every action is recorded with who and when.

| Endpoint | Purpose |
|----------|---------|
| `GET /admin/moderation/reports` | Reports, open first, paged; `openCount` for a badge. |
| `GET /admin/moderation/reports/{id}` | One report: its lines, purged line ids, actions. |
| `POST /admin/moderation/reports/{id}/hide`, `/unhide` | `{"lineIds": [...], "note": "..."}` |
| `POST /admin/moderation/reports/{id}/purge` | `{"lineIds": [...], "confirm": true}`: can't be undone. |
| `POST /admin/moderation/reports/{id}/dismiss` | Closes an open report as not abuse. |
| `GET /admin/moderation/logs?provenance=&day=` | A log day with ids, hidden lines marked. |
| `POST /admin/moderation/lines/hide`, `/unhide` | Lines from a log day rather than a report. |

The shapes are in [Blog HTTP API](../docs/reference/blog-http-api.md#admin-moderation).
