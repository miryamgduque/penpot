# Phase 03 — Creation flow + generation

**Status:** done (2026-07-16) — `propose-reactive` heuristic; the interview's
Mode step is now the On-call/Observer question; `skill_gen` categories →
`["Audits" "Build"]`, prompt + parse carry `:reactive`. Verified live: the
question renders, On-call is the empty-description default, and a watch-ish
description flips the suggested default to Observer.

## Goal

The guided creation interview asks for reactive behavior instead of Mode, and the
generator files skills under the two remaining categories.

## Before Start

- [ ] Re-read `skill-create*` (ui/workspace/ai_panel.cljs:1057) + `propose-mode`
      (:191), and `create-from-answers` (data/workspace/user_skills.cljs).
- [ ] Re-read [skill_gen.cljs](../../../../../../frontend/src/app/main/data/workspace/skill_gen.cljs):
      `categories` (:22), `answers->user-message` (:45), `parse-generation` (:107).

## Checklist

- [ ] `propose-mode` → `propose-reactive`: heuristic returning `"observer"` for
      watch-ish phrasing (audit / watch / keep an eye / monitor / flag / remind),
      else `"on-call"`.
- [ ] `skill-create*`: replace the "Mode" step (suggest/review/autofix buttons)
      with a two-option reactive-behavior control framed as US #14's question —
      *"Should this skill only respond when asked, or quietly keep an eye on things
      and let you know when it notices something?"* — options **On-call** /
      **Observer**, with the proposed default hinted. State `reactive*` replaces
      `mode*`; pass `:reactive` in the answers map.
- [ ] `skill_gen/categories` → `["Audits" "Build"]`; `clamp-category` still falls
      back to `"Audits"`.
- [ ] `answers->user-message`: "Mode:" line → "Reactive behavior:"; keep it
      authoritative (the model doesn't choose it).
- [ ] `parse-generation`: return `:reactive` (from answers) instead of `:mode`;
      key name flows into `create-from-answers` → `:create-skill`.
- [ ] `create-from-answers` / any `:mode` key in the RPC params → `:reactive`
      (aligns with Phase 04's backend rename).
- [ ] Compile 0 warnings; `clj-kondo` clean.

## Notes

- Conversational/follow-up-if-vague is already the interview's style; a proposed
  default + a plain-language two-way choice satisfies it for the prototype.
