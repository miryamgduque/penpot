;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools
  "The agents' native design tools: the provider-agnostic tool
  declarations (`tool-specs`), the `execute-tool` dispatch and the
  playbook nudge. The implementations live in the agent-tools.*
  family namespaces (common / read / structure / layout / components /
  document / tokens / media / agentic / compose)."
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.agent-tools.agentic :as atg]
   [app.main.data.workspace.agent-tools.common :as atc]
   [app.main.data.workspace.agent-tools.components :as atcp]
   [app.main.data.workspace.agent-tools.compose :as atx]
   [app.main.data.workspace.agent-tools.document :as atd]
   [app.main.data.workspace.agent-tools.layout :as atl]
   [app.main.data.workspace.agent-tools.media :as atm]
   [app.main.data.workspace.agent-tools.read :as atr]
   [app.main.data.workspace.agent-tools.structure :as ats]
   [app.main.data.workspace.agent-tools.tokens :as att]
   [beicon.v2.core :as rx]))

;; Re-exports for the production consumers (agent.cljs, ai_panel.cljs,
;; agent_chats.cljs) that predate the family split — they keep requiring
;; this root namespace only.
(def audit-violations atg/audit-violations)
(def register-side-turn-runner! atg/register-side-turn-runner!)
(def submit-pending-form! atg/submit-pending-form!)

;; --- Tool declarations (provider-agnostic; encoded per provider in agent.cljs)

(def tool-specs
  [{:name "read_design"
    :description
    (str "One-call orientation: the current file, page, selection, the page's "
         "TOP-LEVEL shapes, its variant sets (with members and properties), its "
         "components, and its design tokens grouped by type. Call this FIRST "
         "each task to see what is in the file instead of guessing — including "
         "whether a token, component or variant set already exists before "
         "authoring another.\n"
         "It reports one level deep: a shape's `childCount` tells you it has "
         "children without listing them. To reach inside, or when an `omitted` "
         "note says a list was capped, use find_shapes.\n"
         "The SELECTION reports each shape's LOOK — `fills` (solid with the hex "
         "to copy, gradient with its stops, or image with its id), plus any "
         "`radius`, `opacity`, `shadow` and `blur`. Those names are exactly "
         "modify_shape's, so reading a look and writing it back is a straight "
         "copy. The broad shape list omits all of it to stay cheap: use "
         "find_shapes to see any other shape's look.\n"
         "Two things you can read but not write: an image fill (reuse it by "
         "passing its imageId back, never approximate it with a solid) and a "
         "`blur`. Say so rather than shipping a replica that quietly lacks them.")
    :input-schema {:type "object" :properties {}}}

   {:name "find_shapes"
    :description
    (str "Finds shapes anywhere on the page by name (case-insensitive "
         "substring) and/or type — including nested ones read_design does not "
         "list. Use it to reach into a board, or to follow up an `omitted` note. "
         "At least one of name/type is required.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "substring, e.g. \"button\""}
                                :type {:type "string" :enum ["board" "rect" "ellipse" "text" "group" "path" "image"]}}}}

   {:name "get_design_skills"
    :description
    (str "Your playbooks for this file. Called with no argument it lists them "
         "(name, category, mode, what it does) — cheap. Called with a `name` it "
         "returns that skill's FULL playbook: the method, the order to work in, "
         "the critical rules and the checkpoints, plus its `references` — deeper "
         "method documents (layout composition, style profiles, component "
         "recipes…) fetched one at a time by adding `reference`. When a task "
         "matches a skill in your instructions' skills list, fetch it by name "
         "and follow it — do not guess or reconstruct its content from the "
         "blurb.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "return this skill's full playbook"}
                                :reference {:type "string"
                                            :description "with name: return this reference document instead (the playbook cites references/NN-x.md; pass \"NN-x\")"}}}}

   {:name "explore_design"
    :description
    (str "Delegate broad or multi-step READING to a fast side agent: file "
         "maps and inventories, cross-shape audits, \"which screens use X\" "
         "sweeps. It runs the read-only tools in its own context and returns "
         "one compact digest naming shapes, boards, components and tokens by "
         "exact name and id — far cheaper than doing many large reads here.\n"
         "Do NOT use it for a single-shape or single-page lookup (call "
         "read_design or find_shapes directly), and it cannot make changes. "
         "If it errors, fall back to reading the design directly yourself.")
    :input-schema {:type "object"
                   :required ["question"]
                   :properties {:question {:type "string"
                                           :description (str "what to find out, self-contained — the "
                                                             "side agent sees only this text and the file")}}}}

   {:name "ask_user"
    :description
    (str "Ask the user structured questions, rendered as an interactive form in "
         "the chat (option chips, multi-select, free text). Use it when a "
         "playbook calls for an interview or you need several decisions at once; "
         "for one quick question just ask in prose.\n"
         "Ask only what you cannot resolve from the file, the foundations or a "
         "sensible default — and finish all work that does not depend on the "
         "answer BEFORE asking. Put your recommended choice first in each "
         "question's options, and when an answer changes what you will build, "
         "say how in its hint. Never ask permission-style questions (\"should I "
         "proceed?\") — governance already defines the pauses, and a checkpoint "
         "is a report with options, not a request for permission.\n"
         "ONE call per interview (at "
         "most 10 questions) — the turn pauses until the user submits. Give every "
         "question a unique snake_case `id`. The result is {answers: {id: value}}: "
         "a `single` question yields its chosen option string (the user's own "
         "words when they picked Other…), `multi` an array of them, `text` a "
         "string; the literal value \"__decide__\" means the user delegated that "
         "decision to you — choose well and say what you chose. Skipped optional "
         "questions are omitted from the answers. A text question with "
         "allow_images lets the user attach reference images (moodboards, "
         "competitor screens); they arrive as images ON this tool's result, in "
         "question order, with {attachments: {id: count}} tying each to its "
         "question — read them before answering-dependent steps.")
    :input-schema {:type "object"
                   :properties
                   {:title {:type "string"
                            :description "short heading shown above the form"}
                    :questions
                    {:type "array"
                     :items {:type "object"
                             :properties
                             {:id {:type "string" :description "unique snake_case key for this answer"}
                              :question {:type "string" :description "the question itself"}
                              :hint {:type "string" :description "one line of context shown under the question"}
                              :type {:type "string" :enum ["single" "multi" "text"]
                                     :description "single = pick one chip, multi = pick several, text = free text"}
                              :options {:type "array" :items {:type "string"}
                                        :description "the choices, for single/multi"}
                              :allow_other {:type "boolean" :description "offer an Other… free-text chip (default true)"}
                              :allow_decide {:type "boolean" :description "offer a \"Decide for me\" chip (default false)"}
                              :allow_images {:type "boolean" :description "text questions only: let the user attach reference images"}
                              :optional {:type "boolean" :description "the user may leave this unanswered"}}
                             :required ["id" "question" "type"]}}}
                   :required ["questions"]}}

   {:name "set_foundation"
    :description
    (str "Saves (or replaces) ONE of this file's foundations — named standing "
         "design context (Vibes, Tone of voice, Naming, A11y priorities…) "
         "that is inlined into your instructions on every future turn in "
         "this file and shared with every collaborator. `name` picks the "
         "foundation, creating it if new. Write the doc in the DESIGN.md "
         "shape: YAML frontmatter between --- fences with at least `name` "
         "and a one-line `description` (that line becomes the foundation's "
         "card summary), then short markdown guidance. 'Vibes' is the "
         "project's design direction and carries the full token schema — "
         "colors, typography, rounded, spacing, optionally components "
         "referencing tokens as {colors.primary} — with body sections "
         "Overview, Colors, Typography, Layout, Shapes, Do's and Don'ts; "
         "other foundations add tokens only where they earn their per-turn "
         "prompt weight. Validated on save: broken YAML or a {token.ref} "
         "that resolves to nothing is rejected with the reason; stay well "
         "under 6000 characters per foundation — every one is read on every "
         "turn. Pass an empty `doc` to remove the foundation. The file's "
         "current foundations are already in your instructions under "
         "'Foundations'.")
    :input-schema {:type "object"
                   :properties {:name {:type "string"
                                       :description "which foundation (e.g. \"Vibes\", \"Tone of voice\")"}
                                :doc {:type "string"
                                      :description "the full document (empty string removes it)"}}
                   :required ["name" "doc"]}}

   {:name "render_board"
    :description
    (str "Renders boards to images and SHOWS them to you — this is how you "
         "look at the design rather than infer it from JSON. Use it when the "
         "question is visual: does this layout work, do these overlap, is the "
         "spacing even, does that text fit, did my edit land the way I meant. "
         "read_design gives you coordinates; this gives you the picture. "
         "Pass board names (as read_design reports them) or ids; omit `names` "
         "to render the current selection. Up to 5 at once. "
         "Boards and shapes only — there is no way to render 'the page', so "
         "name the boards you want. If it errors, do not retry: say what you "
         "could not see and continue from read_design.")
    :input-schema {:type "object"
                   :properties {:names {:type "array"
                                        :items {:type "string"}
                                        :description "board names or ids; omit for the current selection"}
                                :scale {:type "number"
                                        :description "1-4, default 2; raise only if you need to read small text"}}}}

   {:name "create_shape"
    :description
    (str "Creates a rectangle, ellipse or board (frame) at the given "
         "position/size. Pass parentId to nest it inside a board. Into a "
         "laid-out board, children land in CREATION order (first created = "
         "first in the flow), so build in reading order. Returns the "
         "new shape id. Geometry settles asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:type {:type "string" :enum ["rect" "ellipse" "board"]}
                                :x {:type "number"}
                                :y {:type "number"}
                                :width {:type "number"}
                                :height {:type "number"}
                                :name {:type "string"}
                                :fill {:type "string" :description "solid fill hex, e.g. #6366f1"}
                                :parentId {:type "string" :description "board/group id to nest into"}}
                   :required ["type" "x" "y" "width" "height"]}}

   {:name "insert_image"
    :description
    (str "Fetches an image from an http(s) URL (downloaded server-side) and "
         "places it as an image shape. Ideal for mock content from keyless "
         "placeholder services — photos BY KEYWORD: "
         "https://loremflickr.com/{w}/{h}/{keyword} (e.g. /320/320/pasta — use "
         "this when the content matters: food, portrait, city…), random "
         "photos: https://picsum.photos/{w}/{h} (insert /seed/{word}/ before "
         "the size for a stable pick — the seed does NOT pick the subject), "
         "labeled blocks: "
         "https://placehold.co/{w}x{h}/{bghex}/{fghex}.png?text={label}, "
         "avatars: https://api.dicebear.com/9.x/{style}/png?seed={name} "
         "(styles: lorelei, avataaars, shapes, initials). Any public image URL "
         "works; private hosts are rejected by the server. Omit width/height "
         "to keep the image's intrinsic size, or give just one to scale "
         "preserving aspect ratio. Pass parentId to nest into a board. "
         "Geometry settles asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:url {:type "string" :description "absolute http(s) image URL"}
                                :name {:type "string" :description "layer name, e.g. \"Hero photo\""}
                                :x {:type "number"}
                                :y {:type "number"}
                                :width {:type "number"}
                                :height {:type "number"}
                                :parentId {:type "string" :description "board/group id to nest into"}}
                   :required ["url"]}}

   {:name "search_icons"
    :description
    (str "Searches the Iconify catalog (200k+ open-source icons: Material "
         "Symbols, Lucide, Tabler, Phosphor, Font Awesome…) and returns "
         "matching icon ids in prefix:name form. Use a short noun query like "
         "\"home\" or \"arrow left\". Insert a result with insert_icon.")
    :input-schema {:type "object"
                   :properties {:query {:type "string"}
                                :limit {:type "number" :description "max results, default 24"}}
                   :required ["query"]}}

   {:name "insert_icon"
    :description
    (str "Fetches an Iconify icon by id (prefix:name, from search_icons) and "
         "imports it as vector shapes at the given position (it nests into the "
         "board under that point, if any). Icons arrive monochrome as authored "
         "— usually black; to recolor, bind a color token to the created shape "
         "with apply_tokens rather than passing raw hexes. Geometry settles "
         "asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:icon {:type "string" :description "e.g. lucide:house"}
                                :x {:type "number"}
                                :y {:type "number"}
                                :size {:type "number" :description "height in px, default 24"}
                                :name {:type "string" :description "layer name; defaults to the icon id"}}
                   :required ["icon"]}}

   {:name "search_fonts"
    :description
    (str "Searches the fonts available in this Penpot instance (Google Fonts "
         "plus built-ins) by family name, case-insensitive substring. Returns "
         "each match's id, family, and variant ids. Apply one with set_font.")
    :input-schema {:type "object"
                   :properties {:query {:type "string" :description "e.g. \"inter\" or \"serif family name\""}
                                :limit {:type "number" :description "max results, default 15"}}
                   :required ["query"]}}

   {:name "set_font"
    :description
    (str "Applies a font family (and optionally a specific variant) to text "
         "shapes. Give `family` (case-insensitive, from search_fonts) or a "
         "`fontId`. The font is loaded on demand before applying, and the "
         "text re-measures asynchronously — re-read to confirm geometry.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}
                                           :description "text shape ids"}
                                :family {:type "string" :description "e.g. \"Inter\""}
                                :fontId {:type "string" :description "font id from search_fonts"}
                                :variantId {:type "string" :description "e.g. \"700\" or \"italic\"; defaults to regular"}}
                   :required ["shapeIds"]}}

   {:name "fetch_page"
    :description
    (str "Reads an external web page (fetched server-side; private hosts are "
         "rejected) and answers your question from its text via a cheap side "
         "model. Returns a question-focused digest — never the raw page — so "
         "ask again with a sharper question for more detail. Good for copy, "
         "pricing, docs and other reference content; for visual layout "
         "questions prefer a screenshot.")
    :input-schema {:type "object"
                   :properties {:url {:type "string" :description "absolute http(s) URL"}
                                :question {:type "string" :description "what you need from the page"}}
                   :required ["url" "question"]}}

   {:name "screenshot_page"
    :description
    (str "Screenshots an external web page (rendered server-side in a real "
         "browser; private hosts are rejected) and attaches the PNG to the "
         "conversation. The image is visible for THIS round only — call again "
         "if you need another look. Default is the 1280×800 viewport; "
         "fullPage captures down the page (capped). Use it for visual "
         "reference — layout, colors, branding; use fetch_page for text.")
    :input-schema {:type "object"
                   :properties {:url {:type "string" :description "absolute http(s) URL"}
                                :fullPage {:type "boolean" :description "capture beyond the first viewport"}}
                   :required ["url"]}}

   {:name "get_page_meta"
    :description
    (str "Fetches ONLY a page's structured metadata — title, description, "
         "og:image, favicon and theme-color, with image URLs resolved "
         "absolute. No page text, no side model: the cheap first call for "
         "brand work. Feed the favicon/og:image URL to insert_image and the "
         "theme-color into a proposed token; use fetch_page for content "
         "questions and screenshot_page for the visual.")
    :input-schema {:type "object"
                   :properties {:url {:type "string" :description "absolute http(s) URL"}}
                   :required ["url"]}}

   {:name "modify_shape"
    :description
    (str "Modifies an existing shape: rename, move (x/y), resize (width/height), "
         "set a fill (solid hex, gradient, or an image reused by id) or stroke "
         "color, corner radius, opacity, or a shadow. `fill` takes a descriptor "
         "in exactly the shape read_design reports, so you can read one shape's "
         "fill and hand it straight back to copy it — including an image, which "
         "you reuse by its imageId rather than approximating. "
         "Only the given fields change. A shadow is the real answer for depth or "
         "a hover state — reach for it instead of faking elevation with a paler "
         "fill. Radius and opacity can also be bound to tokens with apply_tokens, "
         "which is preferred for any value that repeats. Also: rotation (absolute "
         "degrees), flipH/flipV, stroke width/style, and hidden/locked. Modifying "
         "a locked shape is refused unless you set locked:false. Position/size "
         "writes return the SETTLED geometry — if it differs from what you "
         "sent, something (a parent layout, a component) owns it; do not "
         "re-send the same numbers. A layout child set absolute takes x/y "
         "relative to its PARENT board, not the page. Stroke width/style, its "
         "dash pattern (strokeDash/strokeGap, in px, with strokeStyle dashed) "
         "and end-caps (strokeCapStart/strokeCapEnd — e.g. circle-marker for a "
         "dot on a line end) are settable too. For several shapes use "
         "update_shapes — one call, one undo step.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :name {:type "string"}
                                :x {:type "number"}
                                :y {:type "number"}
                                :width {:type "number"}
                                :height {:type "number"}
                                :fill {:oneOf [{:type "string" :description "hex, e.g. #6366f1"}
                                               {:type "object"
                                                :description "a fill descriptor, exactly as read_design reports it"
                                                :properties {:type {:type "string" :enum ["solid" "gradient" "image"]}
                                                             :color {:type "string" :description "solid: hex"}
                                                             :opacity {:type "number"}
                                                             :gradient {:type "string" :enum ["linear" "radial"]}
                                                             :stops {:type "array" :items {:type "string"}
                                                                     :description "gradient: hex stops, in order"}
                                                             :imageId {:type "string" :description "image: reuse this raster"}
                                                             :width {:type "number"} :height {:type "number"}
                                                             :mtype {:type "string"}}}]}
                                :stroke {:type "string" :description "hex, e.g. #111111"}
                                :radius {:type "number" :description "corner radius, all four corners"}
                                :opacity {:type "number" :description "0–1"}
                                :shadow {:type "object"
                                         :description "a drop shadow; omit to leave shadows alone"
                                         :properties {:style {:type "string" :enum ["drop-shadow" "inner-shadow"]}
                                                      :offsetX {:type "number"}
                                                      :offsetY {:type "number"}
                                                      :blur {:type "number"}
                                                      :spread {:type "number"}
                                                      :color {:type "string" :description "hex"}
                                                      :opacity {:type "number" :description "0–1"}}}
                                :strokeWidth {:type "number" :description "px"}
                                :strokeStyle {:type "string" :enum ["solid" "dotted" "dashed" "mixed"]}
                                :strokeDash {:type "number" :description "dash length in px (needs strokeStyle dashed); pair with strokeGap"}
                                :strokeGap {:type "number" :description "gap between dashes in px (needs strokeStyle dashed)"}
                                :strokeCapStart {:type "string"
                                                 :enum ["round" "square" "line-arrow" "triangle-arrow" "square-marker" "circle-marker" "diamond-marker" "none"]
                                                 :description "end cap at the line/path START; circle-marker draws a filled dot. none clears it. Only shows on open paths/lines."}
                                :strokeCapEnd {:type "string"
                                               :enum ["round" "square" "line-arrow" "triangle-arrow" "square-marker" "circle-marker" "diamond-marker" "none"]
                                               :description "end cap at the line/path END; e.g. circle-marker for a dot, triangle-arrow for an arrowhead."}
                                :rotation {:type "number" :description "absolute degrees"}
                                :flipH {:type "boolean" :description "flip horizontally (a toggle)"}
                                :flipV {:type "boolean" :description "flip vertically (a toggle)"}
                                :hidden {:type "boolean"}
                                :locked {:type "boolean" :description "a user lock; the agent respects it unless you set false"}}
                   :required ["shapeId"]}}

   {:name "update_shapes"
    :description
    (str "Applies modify_shape-style updates to MANY shapes in one call and "
         "ONE undo step — prefer this over repeated modify_shape (clearing "
         "fills on a batch of layout boards, renaming several layers, nudging "
         "a set of positions). Each entry takes the same fields as "
         "modify_shape. All-or-nothing: one invalid entry rejects the whole "
         "batch, so nothing half-applies. Geometry settles asynchronously — "
         "verify with read_design.")
    :input-schema {:type "object"
                   :properties {:updates {:type "array"
                                          :items {:type "object"
                                                  :description "same fields as modify_shape (shapeId required)"
                                                  :properties {:shapeId {:type "string"}}
                                                  :required ["shapeId"]}}}
                   :required ["updates"]}}

   {:name "nest_shape"
    :description
    (str "Moves a shape into a parent board/group. In a laid-out board `index` "
         "is the position in READING order — 0 = first item of the flow; omit "
         "it to append at the end — the tool translates to Penpot's internal "
         "order, so create/nest in the order you want things to read and never "
         "reach for row-reverse to fix ordering. In a plain board/group, index "
         "is z-order from the back and omitting it puts the shape on top. "
         "Verifies the move actually landed and errors if Penpot refused it.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :parentId {:type "string"}
                                :index {:type "number"
                                        :description "flow position in a laid-out board (0 = first); z-from-back otherwise"}}
                   :required ["shapeId" "parentId"]}}

   {:name "create_text"
    :description
    (str "Creates an auto-width text shape with the given string at x,y. "
         "Optional fill (hex, default black), align, and parentId to nest it "
         "(in a laid-out board, texts land in creation order). "
         "TYPOGRAPHY IS TOKENS: font size, family and weight are set by binding a "
         "token with apply_tokens (author one with create_token type=fontSizes) — "
         "that is how a heading becomes a heading, and how a type scale stays a "
         "scale. Use set_text to change the words later. Width/height settle "
         "asynchronously — re-read to confirm.")
    :input-schema {:type "object"
                   :properties {:text {:type "string"}
                                :x {:type "number"}
                                :y {:type "number"}
                                :name {:type "string"}
                                :fill {:type "string" :description "hex, default #000000"}
                                :align {:type "string" :enum ["left" "center" "right" "justify"]}
                                :parentId {:type "string"}}
                   :required ["text" "x" "y"]}}

   {:name "set_text"
    :description
    (str "Changes an existing text shape's WORDS, and/or its alignment. This is "
         "the only way to edit text after creating it — modify_shape's `name` "
         "renames the layer, it does not touch the words. Font size, family and "
         "weight are not here on purpose: they are design tokens, bound with "
         "apply_tokens (fontSize, fontFamily, fontWeight). Rewriting the words "
         "keeps the existing styling.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :text {:type "string" :description "the new words; \"\" clears them"}
                                :align {:type "string" :enum ["left" "center" "right" "justify"]}}
                   :required ["shapeId"]}}

   {:name "create_component"
    :description
    (str "Turns the given shapes (or the current selection if shapeIds is "
         "omitted) into a reusable component. Returns the new component id.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}}}

   {:name "group_shapes"
    :description
    (str "Groups shapes into a group and returns its id. A group is NOT a board: "
         "it has no layout, no fill and no clipping — it only bundles shapes so "
         "they move together. If you want them arranged or spaced, create a board "
         "(create_shape type=board) and give it set_layout instead; that is the "
         "usual answer when the ask is \"group these and space them out\".")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "ungroup_shapes"
    :description
    (str "Dissolves a group, a board or a boolean, leaving its children in place "
         "where they were. Components and variant containers cannot be ungrouped "
         "— they own their structure.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "delete_shape"
    :description
    (str "Deletes shapes and their children. The counterpart to create_shape — "
         "use it to clean up your own mistakes rather than leaving them in the "
         "file, and to remove what the user asks you to remove. Undoable by the "
         "user with ⌘Z as a single step.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "duplicate_shape"
    :description
    (str "Duplicates shapes, exactly as ⌘D does — which means a duplicated "
         "BOARD lands to the right of its original, while any other shape lands "
         "exactly ON TOP of the one it copied and must be moved to be seen. "
         "Cheaper and more faithful than rebuilding a copy with create_shape. "
         "For copies with different content (titles, images) or copies that "
         "should land inside a laid-out board, use clone_shape — one call does "
         "the duplicate + re-nest + retext chain. To add a variant to an "
         "existing set use add_variant instead — that keeps the copy inside "
         "the set.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "clone_shape"
    :description
    (str "Duplicates a shape N times with per-clone content — the one-call way "
         "to turn a finished card/list-item/tile into a populated set. Each "
         "clone can override the copy's layer name plus, matched BY LAYER NAME "
         "inside the copy, its texts (textByName) and image fills (imageByName "
         "— an http(s) URL to fetch, or an existing imageId to reuse). Pass "
         "parentId to append the clones to a laid-out board's flow in order "
         "(grid cells reseat to match). Prefer this over duplicate_shape + "
         "re-nest + set_text chains. Asynchronous: verify with render_board.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string" :description "the shape to clone (a board/card works best)"}
                                :parentId {:type "string" :description "board to append the clones into (optional)"}
                                :clones {:type "array"
                                         :items {:type "object"
                                                 :properties {:name {:type "string" :description "layer name for this clone"}
                                                              :textByName {:type "object"
                                                                           :description "layer name → new words, e.g. {\"title\": \"Sheet-Pan Quesadillas\"}"}
                                                              :imageByName {:type "object"
                                                                            :description "layer name → image URL or existing imageId"}}}}}
                   :required ["shapeId" "clones"]}}

   {:name "build_tree"
    :description
    (str "Creates a whole nested subtree in ONE call: boards with layouts, "
         "rects/ellipses, texts and images, with token bindings and fonts, "
         "children flowing in the listed (reading) order. THE default way to "
         "build a section or a screen skeleton — plan the structure, send one "
         "tree, then refine with targeted calls; 30 create/nest/set_layout "
         "rounds collapse into one. Node: {type: board|rect|ellipse|text|image, "
         "name, width/height (x/y only for the root), text (type text), url "
         "(type image), fill (hex), layout (set_layout fields, boards only), "
         "layoutChild (set_layout_child fields), tokens ([{name, properties}] "
         "as in apply_tokens), font ({family, variant}, text only), children "
         "(boards only)}. Returns ids keyed by node name. On an error you get "
         "a PARTIAL result naming what exists — repair with targeted calls, "
         "never re-send the tree. Asynchronous: verify with render_board.")
    :input-schema {:type "object"
                   :properties {:parentId {:type "string" :description "existing board to build into (optional)"}
                                :tree {:type "object" :description "the root node"}}
                   :required ["tree"]}}

   {:name "set_layout"
    :description
    (str "Gives a board a flex or grid layout, or updates the one it has — this "
         "is how you arrange children in Penpot. They reflow automatically, so "
         "prefer this over positioning each child with x/y: a laid-out board "
         "survives content changes, hand-placed coordinates do not. Use `type: "
         "grid` for a gallery, a pricing table or any two-dimensional wrap, with "
         "`columns` for how many; flex (the default) for a single row or column. "
         "Children flow in the order you created/nested them (reading order) — "
         "if the order is wrong, fix it with nest_shape index, NEVER by "
         "switching to row-reverse/column-reverse (those are for genuinely "
         "reversed designs). Gaps and padding accept spacing tokens via "
         "apply_tokens (rowGap, columnGap, paddingTop…). Only boards can have a "
         "layout. Asynchronous: verify with read_design.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string" :description "board id"}
                                :type {:type "string" :enum ["flex" "grid"]
                                       :description "default flex; grid for a gallery/table"}
                                :columns {:type "number"
                                          :description "grid only: number of equal columns; omit to infer from the children"}
                                :dir {:type "string" :enum ["row" "column" "row-reverse" "column-reverse"]}
                                :alignItems {:type "string" :enum ["start" "end" "center" "stretch"]}
                                :justifyContent {:type "string"
                                                 :enum ["start" "center" "end" "space-between"
                                                        "space-around" "space-evenly" "stretch"]}
                                :rowGap {:type "number"}
                                :columnGap {:type "number"}
                                :padding {:type "object"
                                          :description "px; any of top/right/bottom/left"
                                          :properties {:top {:type "number"} :right {:type "number"}
                                                       :bottom {:type "number"} :left {:type "number"}}}
                                :wrap {:type "boolean"}
                                :remove {:type "boolean" :description "strip the layout instead"}}
                   :required ["shapeId"]}}

   {:name "set_layout_child"
    :description
    (str "Controls how ONE child behaves inside its parent's flex layout: "
         "whether it grows to fill the row, hugs its content, aligns differently "
         "from its siblings, or leaves the flow entirely (absolute). Penpot's "
         "words are fill / fix / auto — \"fill\" is CSS flex-grow, \"auto\" is "
         "hug-contents. The parent must already have a layout (set_layout); "
         "without one these settings are stored and do nothing. Exception: on a "
         "board that itself has a layout, fix/auto sizing works with no parent — "
         "that is how a top-level board or component hugs its content. An "
         "absolute child is positioned with modify_shape x/y RELATIVE TO ITS "
         "PARENT board, not the page. Margins accept spacing tokens via "
         "apply_tokens. Asynchronous.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :horizontalSizing {:type "string" :enum ["fill" "fix" "auto"]}
                                :verticalSizing {:type "string" :enum ["fill" "fix" "auto"]}
                                :alignSelf {:type "string" :enum ["start" "end" "center" "stretch"]}
                                :margin {:type "object"
                                         :description "px; any of top/right/bottom/left"
                                         :properties {:top {:type "number"} :right {:type "number"}
                                                      :bottom {:type "number"} :left {:type "number"}}}
                                :absolute {:type "boolean" :description "leave the layout flow"}
                                :zIndex {:type "number"}
                                :minWidth {:type "number"} :maxWidth {:type "number"}
                                :minHeight {:type "number"} :maxHeight {:type "number"}}
                   :required ["shapeId"]}}

   {:name "generate_code"
    :description
    (str "Returns Penpot's own markup and CSS for shapes — what the design "
         "actually IS, rather than what it looks like. Use it to compare a "
         "design against shipped code: the canvas shows you the picture, this "
         "shows you the rules. Defaults to the selection and to html markup "
         "(svg is the alternative), and includes children unless you say "
         "otherwise. Large subtrees may be refused as oversized — inspect fewer "
         "shapes or pass includeChildren: false rather than retrying.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}
                                           :description "defaults to the selection"}
                                :type {:type "string" :enum ["html" "svg"]}
                                :includeChildren {:type "boolean" :description "default true"}}}}

   {:name "mask_shapes"
    :description
    (str "Clips shapes to a mask — the circle avatar, the photo cropped to a "
         "card's rounded corner. The topmost shape becomes the mask and clips "
         "the rest; pass it along with what it should clip. Unlike a board "
         "(which only clips to a rectangle) a mask clips to any shape. The "
         "result is a masked group, not a board. Reverse it with unmask_shapes.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "unmask_shapes"
    :description "Removes a mask, leaving its children unclipped. Only a masked group (isMask in read_design) can be unmasked."
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "undo_change"
    :description
    (str "Reverts the single most recent change — the agent's counterpart to "
         "⌘Z, and the way to take back a modify_shape that set the wrong value "
         "(delete_shape only undoes creations). The undo stack is shared with "
         "the user, so this reverts whatever was done last; it is normally your "
         "own last action, but say what you undid and verify with read_design. "
         "Fails while a text/path editor is open or the stack is empty.")
    :input-schema {:type "object" :properties {}}}

   {:name "redo_change"
    :description "Re-applies the most recently undone change — the counterpart to undo_change."
    :input-schema {:type "object" :properties {}}}

   {:name "create_boolean"
    :description
    (str "Combines shapes with a boolean operation — union (merge), difference "
         "(cut the top shapes out of the bottom), intersection (keep the overlap), "
         "exclusion (keep everything except the overlap). Makes one editable "
         "boolean shape; the operands become its children. For \"cut a hole in "
         "this\" when the shapes are already on the canvas. (For an icon, "
         "create_from_svg is usually easier.) Dissolve with ungroup_shapes.")
    :input-schema {:type "object"
                   :properties {:operation {:type "string" :enum ["union" "difference" "intersection" "exclusion"]}
                                :shapeIds {:type "array" :items {:type "string"}}}
                   :required ["operation" "shapeIds"]}}

   {:name "create_page"
    :description
    (str "Adds a page to the file — a place for explorations or a playground to "
         "make a mess on, kept off the main design. Does NOT switch to it; the "
         "other tools keep acting on the current page. read_design lists all "
         "pages.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "optional; defaults to \"Page N\""}}}}

   {:name "switch_page"
    :description
    (str "Makes another page the current one — MOVES the user's canvas there, so "
         "tell them. After this, every other tool acts on the new page. Use it "
         "only when the work is on a different page.")
    :input-schema {:type "object"
                   :properties {:pageId {:type "string"}}
                   :required ["pageId"]}}

   {:name "leave_comment"
    :description
    (str "Posts a comment thread pinned to a point on the canvas — the artifact a "
         "design review produces, unlike ephemeral chat text. OUTWARD-FACING: it "
         "is written as the current user and notifies collaborators, so only use "
         "it when the user asked for a review left ON the file, and make the "
         "content say it is from the design agent (e.g. \"[agent] this fill is a "
         "raw hex; token color.brand.primary exists\"). Position it at the "
         "problem — pass the shape's x/y.")
    :input-schema {:type "object"
                   :properties {:content {:type "string"}
                                :x {:type "number"} :y {:type "number"}}
                   :required ["content"]}}

   {:name "list_comments"
    :description "Lists the file's existing comment threads (position + content), so you don't duplicate one."
    :input-schema {:type "object" :properties {}}}

   {:name "save_version"
    :description
    (str "Saves a named version snapshot of the file — the cheapest insurance "
         "before risky work (a long build session, variant surgery). Undo takes "
         "back the last step; a snapshot takes back the next hundred. Give a "
         "label saying what it is a checkpoint for; it appears in the History "
         "panel. Restoring is the user's move there, not the agent's.")
    :input-schema {:type "object"
                   :properties {:label {:type "string" :description "e.g. \"before variant surgery\""}}
                   :required ["label"]}}

   {:name "switch_variant"
    :description
    (str "Switches a placed variant instance to the member matching a property "
         "value — \"make this button show its hover state\". This is what variant "
         "sets are FOR; use it instead of restyling the copy by hand (which "
         "manufactures override drift). Nearest match if the exact combination "
         "has no member.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string" :description "the placed instance"}
                                :property {:type "string" :description "the axis, e.g. State"}
                                :value {:type "string" :description "e.g. Hover"}}
                   :required ["shapeId" "property" "value"]}}

   {:name "reset_overrides"
    :description "Discards a copy's local changes, snapping it back to its main. Use it to undo drift on an instance."
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}}
                   :required ["shapeId"]}}

   {:name "swap_component"
    :description
    (str "Replaces a placed instance with a different component, keeping its "
         "position — swap a filled button for an outline one. For a component "
         "from a connected library, pass its fileId.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :componentId {:type "string"}
                                :fileId {:type "string" :description "only for a connected library's component"}}
                   :required ["shapeId" "componentId"]}}

   {:name "create_from_svg"
    :description
    (str "Imports an SVG string as real Penpot shapes — the way to draw an icon "
         "or a small illustration. Write the SVG yourself and pass it whole; it "
         "becomes editable shapes (a group when it has several elements), not an "
         "image. For a plain rectangle, ellipse or board use create_shape "
         "instead. The SVG's own fills are kept as-is (icons legitimately carry "
         "raw colors); audit_file still flags them if the file enforces tokens.")
    :input-schema {:type "object"
                   :properties {:svg {:type "string" :description "a complete <svg>…</svg> string"}
                                :x {:type "number"} :y {:type "number"}}
                   :required ["svg"]}}

   {:name "create_instance"
    :description
    (str "Places an instance of an existing component at a point, and returns "
         "its id. The instance stays linked to its main, so editing the main "
         "updates every instance — this is what makes a component library worth "
         "building. PREFER THIS over redrawing a part from primitives whenever a "
         "component already matches: see the components listed by read_design. "
         "For a component from a connected library, pass its fileId too.")
    :input-schema {:type "object"
                   :properties {:componentId {:type "string"}
                                :fileId {:type "string"
                                         :description "only for a connected library's component"}
                                :x {:type "number"} :y {:type "number"}}
                   :required ["componentId" "x" "y"]}}

   {:name "detach_instance"
    :description
    (str "Severs a component copy from its main, making it a plain local group. "
         "A LAST RESORT, and governed: the copy stops following the main forever, "
         "which is the opposite of why the component exists. Prefer editing the "
         "main (every copy follows), or add a variant if the difference is a real "
         "state. If you do detach, TELL THE USER — it is not undone by editing the "
         "main. Cannot detach a main, a variant set member, or a piece of a copy "
         "(detach the copy's root instead).")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string" :description "the copy's root"}}
                   :required ["shapeId"]}}

   {:name "create_variant"
    :description
    (str "Combines two or more main components into a Penpot variant set — the "
         "real thing: a variant container whose members switch by property. The "
         "shapes must already be main components (create_component first) and "
         "must not already belong to a variant set.\n\n"
         "NAME THE COMPONENTS FIRST — the set's name and its properties are "
         "derived from their names, so this is how you design the matrix:\n"
         "  \"Badge / Compact\" + \"Badge / Large\"  -> set \"Badge\", one axis "
         "(Compact | Large)\n"
         "  \"Chip / Small / Hover\" + \"Chip / Large / Default\" -> set \"Chip\", "
         "TWO axes (Small|Large, Hover|Default)\n"
         "The shared leading path becomes the set name; each further segment "
         "becomes another property. Components sharing no path give a set named "
         "\"Component\" — valid, but unreadable. Use create_shape/modify_shape "
         "to name them before combining.\n\n"
         "Input order determines variant order. Never emulate variants by naming "
         "layers \"Prop=Value\" — that is not a variant set. Asynchronous: verify "
         "with read_design.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}
                                           :description
                                           "main-instance ids; defaults to the selection"}}}}

   {:name "add_variant"
    :description
    (str "Adds one more variant to an existing set, by duplicating a member. "
         "Pass the set's container to copy its primary variant, or a specific "
         "member to copy that one — pick whichever is closest to what you want, "
         "since the copy inherits its content and you only modify the difference. "
         "The new variant gets a placeholder value (\"Value N\"): name it with "
         "set_variant_property. To build a set in the first place, name the "
         "components with a shared path and use create_variant. Asynchronous.")
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"
                                          :description "a variant set's container id, or a member's id"}}
                   :required ["shapeId"]}}

   {:name "set_variant_property"
    :description
    (str "Names a variant axis, and sets one member's value on it — turns the "
         "placeholder \"Property 1\" into e.g. \"Size\". Renaming an axis "
         "affects every member of the set (properties are uniform by design); a "
         "value applies only to the component given. New sets already take their "
         "values from the component names, so usually only the axis needs a name. "
         "Find the axis and member ids with read_design. Asynchronous.")
    :input-schema {:type "object"
                   :properties {:variantId {:type "string" :description "the variant container id"}
                                :property {:type "string" :description "the axis's current name, e.g. Property 1"}
                                :rename {:type "string" :description "new name for the axis, e.g. Size"}
                                :componentId {:type "string" :description "required when setting a value"}
                                :value {:type "string" :description "this member's value, e.g. Compact"}}
                   :required ["variantId" "property"]}}

   {:name "create_token"
    :description
    (str "Creates a design token of any type in the file's token library — "
         "color, spacing, borderRadius, sizing, opacity, fontSizes and more. "
         "The target SET must already exist: a fresh file has none, so on the "
         "first token of a session create the sets first (create_token_set: "
         "primitives / semantic / modes/light) and a theme to activate them. "
         "This is how a value becomes reusable: author it here, then bind it to "
         "shapes with apply_tokens. Prefer a token over a literal for any value "
         "that repeats — a spacing token on a layout's gap is as much a token as "
         "a color on a fill. A value may reference another token, e.g. "
         "\"{color.blue.500}\". For a scale or a palette use create_tokens "
         "(plural) — one call instead of one per token.")
    :input-schema {:type "object"
                   :properties {:type {:type "string" :enum att/token-type-names
                                       :description "e.g. color, spacing, borderRadius"}
                                :name {:type "string" :description "e.g. spacing.md, color.brand.primary"}
                                :value {:type "string" :description "e.g. 16, #6366f1, {color.blue.500}"}
                                :set {:type "string"
                                      :description "target set, e.g. \"modes/dark\"; defaults to the file's existing set"}}
                   :required ["type" "name" "value"]}}

   {:name "create_tokens"
    :description
    (str "Creates MANY design tokens in one call — prefer this over repeated "
         "create_token whenever you author a scale or a palette (the usual "
         "case). Entries validate together, including that each target set "
         "exists (create_token_set first on a fresh file); nothing is created "
         "unless everything passes. Values may reference other tokens, even "
         "ones earlier in the same batch, e.g. \"{color.blue.500}\".")
    :input-schema {:type "object"
                   :properties {:set {:type "string"
                                      :description "default target set for entries without their own"}
                                :tokens {:type "array"
                                         :items {:type "object"
                                                 :properties {:name {:type "string"}
                                                              :type {:type "string" :enum att/token-type-names}
                                                              :value {:type "string"}
                                                              :set {:type "string"}}
                                                 :required ["name" "type" "value"]}}}
                   :required ["tokens"]}}

   {:name "create_token_set"
    :description
    (str "Creates a token set. Sets are how one token NAME can carry different "
         "values in different modes: put primitives in one set and duplicate the "
         "semantic names across \"modes/light\" and \"modes/dark\". \"/\" groups "
         "sets. A set does nothing on its own — pair it with a theme that enables "
         "it (create_token_theme), or its tokens never resolve.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "e.g. modes/dark"}}
                   :required ["name"]}}

   {:name "create_token_theme"
    :description
    (str "Creates a token theme: a named switch that enables a set of token sets. "
         "This is how dark mode works — a \"Light\" theme enabling modes/light and "
         "a \"Dark\" theme enabling modes/dark, then activate_theme to flip. "
         "Shapes bound to a token name follow automatically; nothing needs "
         "re-applying.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "e.g. Dark"}
                                :group {:type "string" :description "optional grouping, e.g. modes"}
                                :sets {:type "array" :items {:type "string"}
                                       :description "the set names this theme enables"}}
                   :required ["name" "sets"]}}

   {:name "activate_theme"
    :description
    (str "Toggles a token theme on or off, switching which sets resolve — the "
         "moment dark mode actually happens. Every shape bound to a token name "
         "re-resolves; nothing needs re-applying. read_design lists the themes "
         "and which are active.")
    :input-schema {:type "object"
                   :properties {:name {:type "string"}}
                   :required ["name"]}}

   {:name "audit_file"
    :description
    (str "Scans the current page against the file's active rules "
         "(token-only-colors, layer-naming) and returns the open violations "
         "(rule, shape, reason). Use it to ground a fix-up task and to confirm "
         "your fixes cleared the list.")
    :input-schema {:type "object" :properties {}}}

   {:name "apply_tokens"
    :description
    (str "Binds tokens of ANY type to shapes in batch — the safe path, never "
         "rejected by token-only-colors. Each application names a shape, a "
         "token, and optionally which properties to bind; omit properties for "
         "the token type's default (fill for a color).\n"
         "  color        → fill, strokeColor\n"
         "  spacing      → columnGap, rowGap, paddingTop/Right/Bottom/Left, "
         "marginTop/…\n"
         "  borderRadius → borderRadius, borderRadiusTopLeft/…\n"
         "  sizing       → width, height, minWidth/…\n"
         "A spacing token on a flex board's columnGap is as much a design token "
         "as a color on a fill — reach for this instead of hardcoding numbers "
         "in set_layout. Asynchronous: verify with read_design or audit_file "
         "afterwards, not in the same call.")
    :input-schema {:type "object"
                   :properties {:applications
                                {:type "array"
                                 :items {:type "object"
                                         :properties {:shapeId {:type "string"}
                                                      :tokenName {:type "string" :description "e.g. spacing.md, color.brand.primary"}
                                                      :properties {:type "array"
                                                                   :items {:type "string"}
                                                                   :description "e.g. [\"columnGap\"], [\"fill\"]; omit for the type's default"}}
                                         :required ["shapeId" "tokenName"]}}}
                   :required ["applications"]}}])

;; --- Playbook nudge
;;
;; Chat 2 of the Kahoot session made 223 calls and fetched zero playbooks —
;; a routing index alone does not create demand. This is the deterministic
;; backstop: ONE note, on the first structural mutation of a conversation that
;; loaded no playbook, pointing back at the index. Reading a playbook
;; (get_design_skills with a name) or starting/loading a conversation resets it.

(defonce ^:private playbook-fetched* (atom false))

(defonce ^:private playbook-nudged* (atom false))

(defn reset-playbook-nudge!
  "Called by the panel when a conversation starts or is restored."
  []
  (reset! playbook-fetched* false)
  (reset! playbook-nudged* false))

(defn note-playbook-loaded!
  "The turn-start injection (agent/match-playbook) delivered a playbook —
  the one-shot nudge has nothing left to point at."
  []
  (reset! playbook-fetched* true))

(def ^:private nudged-tools
  ;; the structural mutations a build task starts with — enough to catch the
  ;; session at its first step without nagging every styling call after
  #{"create_shape" "create_text" "set_layout" "insert_image" "insert_icon"})

(defn- with-playbook-nudge
  [tool result]
  (if (and (contains? nudged-tools tool)
           (not @playbook-fetched*)
           (not @playbook-nudged*)
           (map? result))
    (do (reset! playbook-nudged* true)
        (update result :note
                (fn [note]
                  (str note (when note " ")
                       "NOTE: no playbook is loaded in this conversation — if "
                       "this task matches a skill in your index (screens, "
                       "components, tokens, audits, migrations), call "
                       "get_design_skills with its name and follow the method."))))
    result))

;; --- Dispatch

(defn execute-tool
  [name input]
  (when (and (= name "get_design_skills") (:name input))
    (reset! playbook-fetched* true))
  (->> (case name
         "read_design"        (rx/of (atr/read-design))
         "find_shapes"        (atr/find-shapes input)
         "render_board"       (atr/render-board input)
         "get_design_skills"  (atg/get-design-skills input)
         "explore_design"     (atg/explore-design input)
         "fetch_page"         (atg/fetch-page input)
         "get_page_meta"      (atg/get-page-meta input)
         "screenshot_page"    (atg/screenshot-page input)
         "ask_user"           (atg/ask-user input)
         "set_foundation"     (atg/set-foundation input)
         "audit_file"         (atg/audit-file)
         "create_shape"       (atc/create-shape input)
         "insert_image"       (atm/insert-image input)
         "search_icons"       (atm/search-icons input)
         "insert_icon"        (atm/insert-icon input)
         "search_fonts"       (atm/search-fonts input)
         "set_font"           (atm/set-font input)
         "modify_shape"       (ats/modify-shape input)
         "update_shapes"      (ats/update-shapes-batch input)
         "nest_shape"         (ats/nest-shape input)
         "create_text"        (ats/create-text input)
         "set_text"           (ats/set-text input)
         "create_component"   (ats/create-component input)
         "delete_shape"       (ats/delete-shape input)
         "duplicate_shape"    (ats/duplicate-shape input)
         "clone_shape"        (atx/clone-shape-tool input)
         "build_tree"         (atx/build-tree-tool input)
         "group_shapes"       (ats/group-shapes input)
         "ungroup_shapes"     (ats/ungroup-shapes input)
         "set_layout"         (atl/set-layout input)
         "set_layout_child"   (atl/set-layout-child input)
         "generate_code"      (atd/generate-code input)
         "create_instance"    (atcp/create-instance input)
         "create_from_svg"    (ats/create-from-svg input)
         "save_version"       (atd/save-version input)
         "create_boolean"     (ats/create-boolean input)
         "create_page"        (atd/create-page input)
         "switch_page"        (atd/switch-page input)
         "leave_comment"      (atd/leave-comment input)
         "list_comments"      (atd/list-comments input)
         "switch_variant"     (atcp/switch-variant input)
         "reset_overrides"    (atcp/reset-overrides input)
         "swap_component"     (atcp/swap-component input)
         "undo_change"        (atd/undo-change)
         "redo_change"        (atd/redo-change)
         "mask_shapes"        (ats/mask-shapes input)
         "unmask_shapes"      (ats/unmask-shapes input)
         "detach_instance"    (atcp/detach-instance input)
         "create_variant"     (atcp/create-variant input)
         "add_variant"        (atcp/add-variant input)
         "set_variant_property" (atcp/set-variant-property input)
         "create_token"       (att/create-token input)
         "create_tokens"      (att/create-tokens input)
         "create_token_set"   (att/create-token-set input)
         "create_token_theme" (att/create-token-theme input)
         "activate_theme"     (att/activate-theme input)
         "apply_tokens"       (att/apply-tokens input)
         (rx/throw (ex-info (dm/str "Unknown tool: " name) {})))
       (rx/map #(with-playbook-nudge name %))))
