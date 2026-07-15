# Phase 07 — Interview image attachments

**Status:** todo

Added 2026-07-15 mid-execution, on user direction: “Something that could be
useful is asking for attachments (images).” The kickoff screenshots already
hinted at this — the free-text answers carry a small image icon. Reference
images (moodboards, competitor screens, brand assets) are exactly the kind
of answer a vibes interview wants.

## Before Start

- [ ] Verify plan is still valid (no conflicts with other plans/sessions)
- [ ] Phases 02/03/05 are done (schema, form UI, skill body all get touched)

## Checklist

- [ ] Schema: `allow_images` (boolean) on `text` questions; tool description
      explains the images arrive attached to the tool result, in question
      order
- [ ] `elicitation.cljs`: `:images` joins the per-question ui state; an
      `attachments` fn collects `{qid count}` + the flat image vector in
      form order; caps (3 per question, 5 per form — mirrors the composer)
- [ ] Form UI: attach button + thumbnails on `allow_images` questions,
      reusing the composer's `read-images` pipeline (recompress to WebP,
      1568px long edge); remove per thumbnail
- [ ] Submit path: `submit-form` carries the images; `submit-pending-form!`
      resolves `{:answers … :attachments {qid n} :images […]}` — `run-tool`
      already lifts `:images` off a tool result into image blocks (the
      `render_board` path), so the model SEES the references with zero
      turn-loop changes
- [ ] The answers-summary bubble shows the attached thumbnails
      (`append-message` already renders `:images` on user bubbles) —
      `submit-form` gains the images argument
- [ ] Text-only models: the existing `strip-images`/“images omitted” note
      already covers tool-result images — verify, don't rebuild
- [ ] Vibes skill body: differentiation/vibe questions set
      `allow_images: true` and the doc-writing step says to describe what
      the references establish (palette, density, mood) in the Vibe section
- [ ] Compile green; commit `:sparkles:`

## After Finish

- [ ] Rename to `done-`, update README links
- [ ] Note discoveries below

## Files

- `frontend/src/app/main/data/workspace/agent_tools.cljs`
- `frontend/src/app/main/data/workspace/elicitation.cljs`
- `frontend/src/app/main/data/workspace/ai_panel.cljs`
- `frontend/src/app/main/ui/workspace/ai_panel.cljs`
- `frontend/src/app/main/data/workspace/agent_skills.cljs`

## Notes

- Payload budget: interview references ride the tool result and are
  re-encoded on later rounds like render_board images — same accepted debt,
  and the WebP recompression keeps each attachment small. The 4M-char guard
  in `build-round-body` still backstops it.
