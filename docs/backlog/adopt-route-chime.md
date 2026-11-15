# Adopt HUB-040: an announcement that starts with a chime

**Status:** Proposed, not started.

**Origin:** HUB-040 (API 0.1.34) lets a route start with a short sound; the hub's backlog item
`route-chime.md` names announcements as the main use.

**Depends on:** hub 0.1.34. The extension raises `multiroom.require_api_version` to 0.1.34 only when it
uses the new API (`RouteOptions.chime`). Related: the `broadcast` group (HUB-036) as the default target.

**Effort:** Small.

## Problem

An announcement cannot ask for a chime, so a spoken message to a quiet room starts without the attention
sound the hub can now play. The jar built against 0.1.33 already loads on a 0.1.34 hub (checked on the
production Pi, 2026-11-15) and announces as before.

## Proposal

- Add an optional `chime` to the speak request (an id from `GET /api/v2/chimes`, or `none`) and pass it in
  `RouteOptions` when the announcement's route is created.
- Leave it absent by default: the hub then uses the input, output or group setting, and plays a chime
  only over silence, never over music being ducked.
- Reject an unknown id with the hub's 400 message rather than a local list.

## Open questions

1. Should a provider-independent default chime be configurable for announcements, or is the hub's
   per-target setting enough?
2. Does the chime count toward the announcement's start-up latency budget the cache hit was built to save?
