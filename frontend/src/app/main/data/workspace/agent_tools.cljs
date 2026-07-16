;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.agent-tools
  "The Agents' native design tools — the CLJS port of the plugin tools in
  `ai-skills/src/ui/agent.ts` + `plugin.ts`. Each tool reads or mutates the
  workspace through Penpot's internal APIs (no plugin runtime, no
  `execute_code`).

  `execute-tool` returns an rx observable of the tool result (a
  JSON-serializable value) or an observable that errors with a
  `{:rule …}`-tagged ex-info (used by the token-only-colors enforcement in a
  later phase). Read tools resolve synchronously; mutating tools (later
  phases) settle asynchronously through the changes pipeline.

  Phase 02 ships the read-only orientation tool `read_design`; structural,
  text/component, token, and audit tools arrive in the following phases."
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as cb]
   [app.common.files.helpers :as cfh]
   [app.common.files.variant :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.agent-skills :as ask]
   [app.main.data.workspace.design-doc :as dd]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt-text]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.util.code-gen :as cg]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(declare audit-violations)
;; lives with the token tools it is built from, but read_design (far above them)
;; is its only caller
(declare sets-and-themes)

;; Composite types carry structured values (typography is a map of font
;; attributes, shadow a vector of shadow maps). `:value` is `::sm/any`, so
;; `make-token` would accept a plain string for one and author something
;; malformed without complaint — better to not offer them than to offer a lie.
(def ^:private composite-token-types #{:typography :shadow})

(def token-type-names
  "The DTCG type names `create_token` offers, derived from
  `cto/token-type->dtcg-token-type` rather than retyped — a hand-copied list
  drifts the next time Penpot adds a type. Declared here because `tool-specs`
  reads it for the enum."
  (->> cto/token-type->dtcg-token-type
       (remove (fn [[k _]] (contains? composite-token-types k)))
       (map second)
       (sort)
       (vec)))

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
         "the critical rules and the checkpoints. When a task matches a skill in "
         "your instructions' skills list, fetch it by name and follow it — do "
         "not guess or reconstruct its content from the blurb.")
    :input-schema {:type "object"
                   :properties {:name {:type "string" :description "return this skill's full playbook"}}}}

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
         "for one quick question just ask in prose. ONE call per interview (at "
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

   {:name "set_design_doc"
    :description
    (str "Saves (or replaces) this project's vibes document — a design.md "
         "that is inlined into your instructions on every future turn in this "
         "file and shared with every collaborator. Write concise markdown: "
         "identity in a sentence, vibe words, audience, platform, what to "
         "design first, voice, do / don't. Stay well under 4000 characters — "
         "it is read on every turn. Call it at the end of a vibes interview "
         "or when the user asks to change the project's design direction; "
         "pass an empty `doc` to delete the document. The current doc, if "
         "any, is already in your instructions under 'Project vibes'.")
    :input-schema {:type "object"
                   :properties {:doc {:type "string"
                                      :description "the full markdown document (empty string deletes)"}}
                   :required ["doc"]}}

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
         "position/size. Pass parentId to nest it inside a board. Returns the "
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
         "placeholder services — photos: https://picsum.photos/{w}/{h} (insert "
         "/seed/{word}/ before the size for a stable pick), labeled blocks: "
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
         "which is preferred for any value that repeats. Geometry settles "
         "asynchronously — re-read to confirm.")
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
                                                      :opacity {:type "number" :description "0–1"}}}}
                   :required ["shapeId"]}}

   {:name "nest_shape"
    :description
    "Moves a shape into a parent board/group at an optional index (default 0)."
    :input-schema {:type "object"
                   :properties {:shapeId {:type "string"}
                                :parentId {:type "string"}
                                :index {:type "number"}}
                   :required ["shapeId" "parentId"]}}

   {:name "create_text"
    :description
    (str "Creates an auto-width text shape with the given string at x,y. "
         "Optional fill (hex, default black), align, and parentId to nest it. "
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
         "Cheaper and more faithful than rebuilding a copy with create_shape. To "
         "add a variant to an existing set use add_variant instead — that keeps "
         "the copy inside the set.")
    :input-schema {:type "object"
                   :properties {:shapeIds {:type "array" :items {:type "string"}}}
                   :required ["shapeIds"]}}

   {:name "set_layout"
    :description
    (str "Gives a board a flex or grid layout, or updates the one it has — this "
         "is how you arrange children in Penpot. They reflow automatically, so "
         "prefer this over positioning each child with x/y: a laid-out board "
         "survives content changes, hand-placed coordinates do not. Use `type: "
         "grid` for a gallery, a pricing table or any two-dimensional wrap, with "
         "`columns` for how many; flex (the default) for a single row or column. "
         "Gaps and padding accept spacing tokens via apply_tokens (rowGap, "
         "columnGap, paddingTop…). Only boards can have a layout. Asynchronous: "
         "verify with read_design.")
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
         "without one these settings are stored and do nothing. Margins accept "
         "spacing tokens via apply_tokens. Asynchronous.")
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
         "This is how a value becomes reusable: author it here, then bind it to "
         "shapes with apply_tokens. Prefer a token over a literal for any value "
         "that repeats — a spacing token on a layout's gap is as much a token as "
         "a color on a fill. A value may reference another token, e.g. "
         "\"{color.blue.500}\".")
    :input-schema {:type "object"
                   :properties {:type {:type "string" :enum token-type-names
                                       :description "e.g. color, spacing, borderRadius"}
                                :name {:type "string" :description "e.g. spacing.md, color.brand.primary"}
                                :value {:type "string" :description "e.g. 16, #6366f1, {color.blue.500}"}
                                :set {:type "string"
                                      :description "target set, e.g. \"modes/dark\"; defaults to the file's existing set"}}
                   :required ["type" "name" "value"]}}

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

;; --- read_design

;; --- read_design's budget
;;
;; Tool results are hard-capped at 20k chars (`agent/max-tool-result-chars`), and
;; `read_design` is the most-called tool we have. Every list it returns is
;; bounded, and every bound announces itself: a list silently cut is read as the
;; whole file, which is worse than a short list that says it is short.

(def ^:private max-listed 60)

;; --- shape types
;;
;; The agent says "board"; the file model says `:frame`. Two mappings, and the
;; difference matters: `create_shape` offers three types and defaults anything
;; else to `:rect` (a lenient default for a tool that only takes three), while
;; `find_shapes` searches the whole vocabulary — there, an unknown type must find
;; NOTHING rather than quietly become `:rect` and return every rectangle on the
;; page as if they were what was asked for.

(defn- shape-type
  "`create_shape`'s type → Penpot's. Defaults to `:rect`; safe only because that
  tool offers exactly board / rect / ellipse."
  [type]
  (case type
    "board"   :frame
    "ellipse" :circle
    :rect))

;; --- Shared message helpers
;;
;; Defined once, here, above every user: these were scattered through the file
;; and each new tool that reached for one hit a forward reference. They exist
;; because of the plan's standing rule — a rejection has to name the shape and
;; the fix, so almost every tool needs them.

(defn- shape-label
  "How a shape is named back to the agent: \"Name\" (id). Both halves matter —
  the name so the message is readable, the id so it is actionable."
  [shape]
  (dm/str "\"" (:name shape) "\" (" (dm/str (:id shape)) ")"))

(defn- labels
  "Several shapes named back to the agent, so one message can list every
  offender and cost one retry instead of one per shape."
  [shapes]
  (str/join ", " (map shape-label shapes)))

(defn- enum-problem
  [tool param value allowed]
  (when (and (some? value) (not (contains? allowed value)))
    (dm/str tool ": " param " \"" value "\" is not valid — use one of: "
            (str/join ", " (sort allowed)))))

(def ^:private text-aligns #{"left" "center" "right" "justify"})

(def ^:private searchable-types
  "`find_shapes`' vocabulary → Penpot's. No default: an unmapped name resolves to
  nil and matches nothing."
  {"board" :frame "frame" :frame
   "rect" :rect "rectangle" :rect
   "ellipse" :circle "circle" :circle
   "text" :text
   "group" :group
   "path" :path
   "image" :image
   "bool" :bool})

(defn bounded
  "`[items omission]` — at most `limit` of `coll`, plus a note when it held back.
  The note names `find_shapes` because a cut the agent cannot act on is just a
  cut."
  [coll limit what]
  (let [total (count coll)]
    (if (<= total limit)
      [(vec coll) nil]
      [(vec (take limit coll))
       (dm/str "showing " limit " of " total " " what
               " — use find_shapes to query the rest by name or type")])))

(defn fill-summary
  "A shape's paint, as compact descriptors the agent can recognise and copy —
  not the raw attrs. Exactly one of `:fill-color` / `:fill-color-gradient` /
  `:fill-image` is set per fill (`cfl/valid-fill-attrs`).

  The image case is the point: a raster cannot be reproduced from JSON, so the
  useful answer is \"this is an image, here is its id, name and size\" rather
  than silence — which is what the agent hit when it said it could see the fill
  but could not inspect it."
  [fills]
  (into []
        (keep (fn [{:keys [fill-color fill-opacity fill-color-ref-id
                           fill-color-gradient fill-image]}]
                (cond
                  (some? fill-image)
                  (cond-> {:type "image"
                           :imageId (dm/str (:id fill-image))
                           :width (:width fill-image)
                           :height (:height fill-image)}
                    (:name fill-image)  (assoc :name (:name fill-image))
                    (:mtype fill-image) (assoc :mtype (:mtype fill-image)))

                  (some? fill-color-gradient)
                  {:type "gradient"
                   :gradient (some-> (:type fill-color-gradient) name)
                   :stops (mapv :color (:stops fill-color-gradient))}

                  (some? fill-color)
                  (cond-> {:type "solid" :color fill-color}
                    ;; only when it is not the default — every fill would carry
                    ;; `opacity: 1` otherwise, for nothing
                    (and (some? fill-opacity) (not= 1 fill-opacity))
                    (assoc :opacity fill-opacity)
                    ;; a token-bound fill is a reference to reuse, not a raw
                    ;; colour to copy — the distinction audit-tokens looks for
                    (some? fill-color-ref-id)
                    (assoc :fromToken true)))))
        fills))

(defn effect-summary
  "A shape's effects, in **Phase 14's own write-param names** — `radius`,
  `opacity`, `shadow {style offsetX offsetY blur spread color opacity}` — so a
  read can be handed straight back to `modify_shape` with no translation.

  Only present, non-default attrs are emitted: every shape carries `r1..r4 = 0`
  and `opacity 1`, and reporting those would spend the budget saying nothing.

  `blur` and `blendMode` are reported although `modify_shape` cannot write them.
  The transcript's complaint was a *glow* it could see but not inspect — a blur
  IS that glow. Naming what cannot be reproduced is the same honesty Phase 17
  gives an image fill: better a replica that says \"the original also has an 8px
  blur\" than one that silently drops it."
  [shape]
  (let [{:keys [r1 r2 r3 r4 opacity blend-mode blur shadow]} shape
        live   (remove :hidden shadow)
        one    (fn [s] (cond-> {:style (some-> (:style s) name)
                                :offsetX (:offset-x s)
                                :offsetY (:offset-y s)
                                :blur (:blur s)
                                :spread (:spread s)
                                :color (:color (:color s))}
                         (some? (:opacity (:color s)))
                         (assoc :opacity (:opacity (:color s)))))
        radius (when (some pos? [r1 r2 r3 r4])
                 (if (apply = [r1 r2 r3 r4])
                   r1
                   ;; Phase 14 writes ONE radius for all four, so a mixed radius
                   ;; is not copyable in a single call — reporting a single
                   ;; number here would be a plausible lie
                   {:topLeft r1 :topRight r2 :bottomRight r3 :bottomLeft r4}))]
    (cond-> {}
      (some? radius)                     (assoc :radius radius)
      (and (some? opacity) (not= 1 opacity)) (assoc :opacity opacity)
      (and (some? blend-mode) (not= :normal blend-mode))
      (assoc :blendMode (name blend-mode))

      (and (some? blur) (not (:hidden blur)))
      (assoc :blur {:type (some-> (:type blur) name) :value (:value blur)})

      (= 1 (count live)) (assoc :shadow (one (first live)))
      (< 1 (count live)) (assoc :shadows (mapv one live)))))

(defn summarize-shape
  "The shape as the agent sees it. Variant keys are added only when truthy —
  `read_design` is called constantly, so a file without variants should not pay
  for the feature in every payload.

  `:look?` — a shape's paint and effects — is off by default and on for the two
  places the look is actually wanted: the **selection** (\"replicate this\") and
  **find_shapes** hits (drill-in). On every shape of the broad list it cost ~11%
  of the 20k budget for a 59-shape file; the constant-cost orientation call stays
  about *what is here*, not *what it looks like*."
  ([objects id] (summarize-shape objects id nil))
  ([objects id {:keys [look?]}]
   (let [shape   (get objects id)
         kids    (count (:shapes shape))
         fills   (when look? (fill-summary (:fills shape)))
         effects (when look? (effect-summary shape))]
     (cond-> {:id (dm/str id)
              :name (:name shape)
              :type (some-> (:type shape) name)
              :x (:x shape)
              :y (:y shape)
              :width (:width shape)
              :height (:height shape)}
       ;; so the agent can tell an empty board from one it cannot see into
       (pos? kids)
       (assoc :childCount kids)

       (seq fills)
       (assoc :fills fills)

       (seq effects)
       (merge effects)

       (ctc/is-variant-container? shape)
       (assoc :isVariantContainer true)

       (ctc/is-variant? shape)
       (assoc :variantId (dm/str (:variant-id shape))
              :variantName (:variant-name shape))

       (:variant-error shape)
       (assoc :variantError (:variant-error shape))))))

(defn- token-summary
  [t]
  (let [value    (str (:value t))
        resolved (some-> (:resolved-value t) str)]
    (cond-> {:name (:name t) :value value}
      ;; only when it differs: an alias's reference is the interesting half, and
      ;; showing only the resolved value hides that this is a reference at all
      (and (some? resolved) (not= resolved value))
      (assoc :resolvedValue resolved))))

(defn tokens-by-type
  "The file's active tokens, grouped under the same public DTCG type names
  `create_token` accepts — so a type read here can be passed straight back.
  Types with no tokens are absent rather than empty."
  [tokens]
  (reduce-kv (fn [acc type toks]
               (assoc acc (get cto/token-type->dtcg-token-type type (name type))
                      (mapv token-summary toks)))
             {}
             (group-by :type tokens)))

(defn shape-matches?
  "Does this shape match the query? `name` is a case-insensitive substring;
  `type` accepts the agent's own vocabulary (\"board\" for `:frame`, as
  `create_shape` takes). An empty query matches nothing — a query that matched
  everything would be a dump, which is what bounding exists to prevent."
  [shape {:keys [name type]}]
  (let [want (some->> type (str/lower) (get searchable-types))]
    (and (or (some? name) (some? type))
         (or (nil? name)
             (and (:name shape)
                  (str/includes? (str/lower (:name shape)) (str/lower name))))
         ;; `type` given but unmapped leaves `want` nil, which matches nothing —
         ;; never everything
         (or (nil? type) (= want (:type shape))))))

(defn- find-shapes
  [{:keys [name type] :as query}]
  (if-let [problem
           (cond
             (and (nil? name) (nil? type))
             (str "find_shapes: pass a name and/or a type to search for — an empty"
                  " query would return the whole file, which is what read_design"
                  " is for")

             ;; an unmapped type matches nothing, and "0 found" reads as "none
             ;; exist" — say it was the query that was wrong
             (and (some? type) (nil? (get searchable-types (str/lower type))))
             (dm/str "find_shapes: \"" type "\" is not a shape type — use one of: "
                     (str/join ", " (sort (distinct (keys searchable-types))))))]
    (rx/throw (ex-info problem {}))
    (let [objects (dsh/lookup-page-objects @st/state)
          hits    (->> (vals objects)
                       (filter #(shape-matches? % query))
                       (sort-by :name))
          [items omitted] (bounded hits max-listed "matches")]
      (rx/of (cond-> {:matches (mapv #(summarize-shape objects (:id %) {:look? true}) items)
                      :found (count hits)}
               omitted (assoc :omitted omitted))))))

(defn library-components
  "Every component the agent can instantiate — this file's and every connected
  library's. Variant members are omitted: they are already listed under
  `:variants` with their component ids, and repeating them here would double the
  payload on a set-heavy file."
  [libraries current-file-id]
  (->> libraries
       (mapcat (fn [[file-id file]]
                 (->> (ctkl/components-seq (:data file))
                      (remove ctc/is-variant?)
                      (map (fn [component]
                             (cond-> {:componentId (dm/str (:id component))
                                      :name (:name component)}
                               ;; the local file is the default target, so saying
                               ;; so on every entry is payload for nothing
                               (not= file-id current-file-id)
                               (assoc :fileId (dm/str file-id)
                                      :library (:name file))))))))
       (vec)))

(defn variant-sets
  "Every variant container on the page with its members and their properties —
  the structural view `create_variant` writes and Phase 03/04 target.

  Scans the whole objects map rather than the top level: a container relocated
  into a board still exists, and an agent that cannot see it rebuilds a set that
  is already there. Member order is `find-variant-components`' own (it reverses
  the container's child order deliberately) — do not re-sort it."
  [data objects]
  (->> objects
       (keep (fn [[id shape]]
               (when (ctc/is-variant-container? shape)
                 {:variantId (dm/str id)
                  :name (:name shape)
                  :members (->> (cfv/find-variant-components data objects id)
                                (remove nil?)
                                (mapv (fn [component]
                                        {:componentId (dm/str (:id component))
                                         :name (:name component)
                                         :properties (mapv #(select-keys % [:name :value])
                                                           (:variant-properties component))})))})))
       (vec)))

(defn- read-design
  []
  (let [state    @st/state
        file-id  (:current-file-id state)
        page     (dsh/lookup-page state)
        objects  (dsh/lookup-page-objects state)
        data     (dsh/lookup-file-data state)
        top-ids  (get-in objects [uuid/zero :shapes])
        selected (dsh/get-selected-ids state)
        variants (variant-sets data objects)
        comps    (library-components (dsh/lookup-libraries state) file-id)

        [shapes shapes-omitted]     (bounded top-ids max-listed "top-level shapes")
        [variants variants-omitted] (bounded variants max-listed "variant sets")
        [comps comps-omitted]       (bounded comps max-listed "components")

        omitted (cond-> {}
                  shapes-omitted   (assoc :shapes shapes-omitted)
                  variants-omitted (assoc :variants variants-omitted)
                  comps-omitted    (assoc :components comps-omitted))]
    (cond-> {:file (get-in state [:files file-id :name])
             :page (:name page)
             :selection (mapv #(summarize-shape objects % {:look? true}) selected)
             :shapes (mapv #(summarize-shape objects %) shapes)
             :variants variants
             :components comps
             :tokens (tokens-by-type (some-> data :tokens-lib ctob/get-tokens-in-active-sets vals))
             :skills (ask/catalog-manifest state)
             ;; a boolean, not the text: the doc itself is already inlined in
             ;; the system prompt, and repeating 4k chars in every read_design
             ;; result would double-bill it
             :hasDesignDoc (some? (dd/get-doc state))
             :openViolations (count (audit-violations state))}
      ;; sets/themes only when the file has any — a single-set file pays nothing
      (seq (sets-and-themes state))
      (merge (sets-and-themes state))

      ;; only when something was actually held back — an always-present "nothing
      ;; omitted" key is payload for nothing
      (seq omitted) (assoc :omitted omitted))))

;; --- render_board
;;
;; The one tool that returns pixels. `render-shape-pixels` draws to a dedicated
;; export surface rather than the viewport, so a board that is scrolled away or
;; zoomed past renders identically — verified byte-identical in Phase 01.
;;
;; It is per-SHAPE: `_render_shape_pixels` takes `(id, scale)` and there is no
;; rectangle, so there is no "render this region" and no "render the page" (the
;; root frame is 0.01×0.01 and returns a 1×1 PNG, silently).

(def ^:private max-render-boards 5)
;; Phase 01 measured scale 2 as the sweet spot: text already legible, ~15kB,
;; ~23ms. Scale 8 costs a 112ms synchronous main-thread block and buys nothing
;; visible.
(def ^:private default-render-scale 2)

(defn- uint8->base64
  "The WASM side hands back raw PNG bytes; the wire wants base64.

  Chunked because `String.fromCharCode` is variadic — spreading a whole
  200kB image across the argument list overflows the stack. 32k is comfortably
  under every engine's limit."
  [^js bytes]
  (let [len (.-length bytes)
        buf (js/Array.)]
    (loop [i 0]
      (when (< i len)
        (let [end (min len (+ i 32768))]
          (.push buf (.apply js/String.fromCharCode nil (.subarray bytes i end)))
          (recur end))))
    (js/btoa (.join buf ""))))

(defn- render-available?
  "The real precondition is the WASM renderer being live for this file — NOT
  `:wasm-export`, which gates Penpot's own export feature, is undeclared in
  `all-flags`, and is off everywhere. Without render-wasm the shape tree is not
  loaded and the call aborts the WASM module rather than returning."
  [state]
  (features/active-feature? state "render-wasm/v1"))

(defn- resolve-board
  "Accepts an id or a name. Names are what the model actually has — `read_design`
  reports them — and it should not have to care that Penpot thinks in uuids."
  [objects term]
  ;; `parse*` and not `uuid/uuid`: the latter is documented UNSAFE and will
  ;; happily build a nonsense uuid out of a board called \"Card\"
  (or (some->> (uuid/parse* term) (get objects) :id)
      (->> (vals objects)
           (filter #(= term (:name %)))
           (first)
           (:id))))

(defn- render-board
  [{:keys [names scale]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        scale   (-> (or scale default-render-scale) (max 1) (min 4))
        terms   (or (seq names)
                    (->> (dsh/get-selected-ids state) (mapv str)))]
    (cond
      (not (render-available? state))
      (rx/throw (ex-info (str "Rendering is unavailable: this file is using the SVG renderer. "
                              "The user can switch it on in Settings › Options › "
                              "\"Use WebGL renderer\". Carry on with read_design instead.")
                         {}))

      (empty? terms)
      (rx/throw (ex-info "Nothing to render: pass board names, or ask the user to select something." {}))

      :else
      (let [terms   (vec (take max-render-boards terms))
            results (mapv (fn [term]
                            (let [id (resolve-board objects term)]
                              (cond
                                (nil? id)
                                {:name term :error "no shape with that name or id"}

                                ;; The page root has no dimensions, so rendering
                                ;; it returns a 1×1 PNG with no error at all —
                                ;; the agent would "see the page", get a single
                                ;; grey pixel, and describe it in good faith.
                                ;; Better a refusal that names the alternative.
                                (= id uuid/zero)
                                {:name term
                                 :error (str "the page as a whole cannot be rendered — "
                                             "name the boards you want instead, e.g. "
                                             (->> (get-in objects [uuid/zero :shapes])
                                                  (keep #(:name (get objects %)))
                                                  (take 3)
                                                  (str/join ", ")))}

                                :else
                                (try
                                  (let [bytes (wasm.api/render-shape-pixels id scale)]
                                    {:name (or (:name (get objects id)) term)
                                     :id (str id)
                                     ;; base64 of the PNG the WASM side wrote
                                     :data (uint8->base64 bytes)})
                                  (catch :default cause
                                    {:name term :error (or (ex-message cause) "render failed")})))))
                          terms)
            ok      (filterv :data results)
            failed  (filterv :error results)]
        (if (empty? ok)
          (rx/throw (ex-info (str "Could not render: "
                                  (str/join "; " (map #(str (:name %) " — " (:error %)) failed)))
                             {}))
          ;; `:images` is lifted out before the rest is JSON-stringified into the
          ;; tool result — otherwise 200kB of base64 would be truncated into the
          ;; 20k-char content string and the model would see a mangled prefix.
          (rx/of {:images (mapv (fn [r] {:mtype "image/png" :data (:data r)}) ok)
                  :rendered (mapv (fn [r] (select-keys r [:name :id])) ok)
                  :failed (when (seq failed) (mapv #(select-keys % [:name :error]) failed))
                  :scale scale}))))))

;; --- Structural tools (create / modify / nest)
;;
;; Each mutation is emitted through the internal changes pipeline, so it is a
;; normal, undoable Penpot edit. Writes apply to the store synchronously (a
;; following read_design sees them); geometry via the transform modifiers and
;; WASM rendering settle on a later tick — hence the "verify with read_design"
;; note in each result.

(defn- interrupt!
  "Clear any transient drawing/edition state before a structural mutation —
  operating mid-drawing leaves the path/draw overlay mounted with stale data
  and crashes its renderer."
  []
  (st/emit! :interrupt))


;; --- token-only-colors enforcement (tool boundary)
;;
;; Port of skills-core/src/guard.ts. When the file enforces `token-only-colors`,
;; the color-setting tools accept only colors that are a design token value or a
;; library color; a raw hex is rejected with a rule-tagged error the agent
;; recovers from by using create_token + apply_tokens. Which rules are
;; enforced is read from `[:ai-panel <file-id> :enforced-rules]` (populated by
;; the backend skills resolution — phase 07).

(defn- normalize-hex
  "Lowercase, `#`-prefixed 6-digit hex, or nil for a non-hex value."
  [s]
  (when (string? s)
    (let [h (cond-> (str/lower (str/trim s))
              (str/starts-with? (str/lower (str/trim s)) "#") (subs 1))]
      (cond
        (= 3 (count h)) (dm/str "#" (apply str (mapcat #(list % %) h)))
        (= 6 (count h)) (dm/str "#" h)
        :else nil))))

(defn- allowed-colors
  "Set of normalized hexes allowed under token-only-colors: color-token values
  in active sets + the file's library colors."
  [state]
  (let [fdata (dsh/lookup-file-data state)
        token-values (some->> (:tokens-lib fdata)
                              (ctob/get-tokens-in-active-sets)
                              (vals)
                              (filter #(= :color (:type %)))
                              (keep #(or (:resolved-value %) (:value %))))
        library-colors (->> (vals (:colors fdata)) (keep :color))]
    (into #{} (keep normalize-hex) (concat token-values library-colors))))

(defn- rule-enforced?
  [state rule]
  (when-let [file-id (:current-file-id state)]
    (contains? (dm/get-in state [:ai-panel file-id :enforced-rules]) rule)))

;; --- Fills, written
;;
;; The write shape mirrors `fill-summary`'s read shape, so a descriptor read out
;; of `read_design` can be handed straight back: read → copy → write round-trips,
;; which is exactly what "replicate this element" needs. A bare hex string still
;; works — it was the shipped param.

(def ^:private fill-types #{"solid" "gradient" "image"})

(defn fill-colors
  "Every raw color a fill param carries. A gradient is several colors in a trench
  coat: if the guard only ever saw `fill` as a string, every stop would walk past
  `token-only-colors` untouched."
  [fill]
  (cond
    (string? fill) [fill]
    (map? fill) (case (:type fill)
                  "gradient" (into [] (keep identity) (:stops fill))
                  "image" []
                  (into [] (keep identity) [(:color fill)]))
    :else []))

(defn fill-problem
  "Why this fill cannot be written, or nil. Pure."
  [fill]
  (when (map? fill)
    (let [{:keys [type color stops imageId]} fill]
      (cond
        (not (contains? fill-types type))
        (dm/str "fill: \"" type "\" is not a fill type — use one of: "
                (str/join ", " (sort fill-types)))

        (and (= "solid" type) (not (string? color)))
        "fill: a solid fill needs a color, e.g. {type: \"solid\", color: \"#6366f1\"}"

        (and (= "gradient" type) (< (count stops) 2))
        "fill: a gradient needs at least 2 stops, e.g. {type: \"gradient\", stops: [\"#000000\", \"#ffffff\"]}"

        (and (= "image" type) (not (string? imageId)))
        (str "fill: an image fill needs an imageId — read one off an existing "
             "image fill with read_design or find_shapes; this reuses that raster "
             "rather than approximating it")

        (and (= "image" type) (nil? (parse-uuid imageId)))
        (dm/str "fill: \"" imageId "\" is not a valid imageId")))))

(defn fill->shape
  "Penpot's `:fills` vector for a fill param — a hex string or a descriptor."
  [fill]
  (cond
    (string? fill)
    [{:fill-color fill :fill-opacity 1}]

    (map? fill)
    (case (:type fill)
      "gradient"
      (let [stops (vec (:stops fill))
            n     (max 1 (dec (count stops)))]
        [{:fill-color-gradient
          {:type (keyword (or (:gradient fill) "linear"))
           :start-x 0 :start-y 0 :end-x 0 :end-y 1 :width 1
           ;; spread across the axis — every stop at offset 0 is not a gradient
           :stops (into [] (map-indexed (fn [i c] {:color c :offset (/ i n)})) stops)}}])

      "image"
      [{:fill-image (cond-> {:id (parse-uuid (:imageId fill))
                             :width (or (:width fill) 0)
                             :height (or (:height fill) 0)
                             :mtype (or (:mtype fill) "image/png")}
                      (:name fill) (assoc :name (:name fill)))}]

      [{:fill-color (:color fill) :fill-opacity (or (:opacity fill) 1)}])))

(defn input-colors
  "Every raw color a `create_shape` / `modify_shape` call would put on a shape.

  The shadow is why this exists. `token-only-colors` watched only `fill` and
  `stroke`, so widening `modify_shape` to shadows would have opened a second,
  unwatched path for raw hex — and the most-used one, since a shadow is exactly
  where a designer reaches for a one-off color. One collector, so both tools are
  guarded by construction and a param added later cannot slip past — which is
  what let gradient stops join without touching either tool's guard."
  [{:keys [fill stroke shadow]}]
  (into (fill-colors fill)
        (keep identity)
        [stroke (:color shadow)]))

(defn- color-violation
  "Returns a token-only-colors ex-info when `hex` is a raw color that the rule
  forbids, or nil when it is allowed / the rule is not enforced."
  [state hex]
  (when (and (string? hex)
             (seq hex)
             (rule-enforced? state "token-only-colors"))
    (let [allowed (allowed-colors state)]
      (when-not (contains? allowed (normalize-hex hex))
        (ex-info (dm/str "token-only-colors: raw color " hex " is not a design token. "
                         "Create it with create_token (type: color) and bind it with "
                         "apply_tokens, or use an existing token"
                         (when (seq allowed)
                           (dm/str " (allowed: " (str/join ", " (take 20 allowed)) ")"))
                         ".")
                 {:rule "token-only-colors"})))))

(defn- reflow-parent!
  "A child added to a laid-out board must flow into it, or it lands at its raw
  x/y outside the layout — a grid gallery stops being a gallery on the next
  card. `:layout/update` only reflows POSITIONS, so grid needs assign-cells
  first (it auto-adds a track for the orphan); flex just needs the reflow."
  [objects pid]
  (let [parent (get objects pid)]
    (cond
      (ctl/grid-layout? parent)
      (do (st/emit! (dwsh/update-shapes [pid]
                                        (fn [p objs] (-> (ctl/assign-cells p objs)
                                                         (ctl/reorder-grid-children)))
                                        {:with-objects? true}))
          (st/emit! (ptk/data-event :layout/update {:ids [pid]})))

      (ctl/flex-layout? parent)
      (st/emit! (ptk/data-event :layout/update {:ids [pid]})))))

(defn- create-shape
  [{:keys [type x y width height fill parentId] :as input}]
  ;; via input-colors, not `fill` directly: both colour-setting tools share one
  ;; collector, so a param added later cannot quietly bypass the guard
  (if-let [problem (or (some-> (fill-problem fill) (->> (dm/str "create_shape: ")) (ex-info {}))
                       (some #(color-violation @st/state %) (input-colors input)))]
    (rx/throw problem)
    (let [nm      (:name input)
          state   @st/state
          page    (dsh/lookup-page state)
          objects (:objects page)
          pid     (some-> parentId parse-uuid)
          parent? (boolean (and pid (contains? objects pid)))
          shape   (cond-> (cts/setup-shape
                           (cond-> {:type (shape-type type)
                                    :x (or x 0) :y (or y 0)
                                    :width (or width 100) :height (or height 100)}
                             nm (assoc :name nm)))
                    fill    (assoc :fills (fill->shape fill))
                    parent? (assoc :parent-id pid :frame-id pid))
          changes (-> (cb/empty-changes)
                      (cb/with-page page)
                      (cb/with-objects objects)
                      (cb/add-object shape))]
      (interrupt!)
      (st/emit! (dch/commit-changes changes))
      (when parent?
        (reflow-parent! objects pid))
      (rx/of {:id (dm/str (:id shape))
              :type type
              :parentId (when parent? (dm/str pid))
              :note "created — verify geometry with read_design"}))))

;; --- insert_image

(def ^:private http-url-re
  #"(?i)^https?://\S+$")

(defn insert-image-problem
  "Validation message for an insert_image call, nil when acceptable. Public for
  tests."
  [{:keys [url]}]
  (when-not (and (string? url) (re-matches http-url-re url))
    (str "insert_image: url must be an absolute http(s) URL, e.g. "
         "https://picsum.photos/600/400.")))

(defn image-geometry
  "Final geometry for the image shape: intrinsic media size unless overridden;
  a single given dimension scales the other to keep the image's aspect ratio.
  Public for tests."
  [{:keys [x y width height]} media]
  (let [mw (:width media)
        mh (:height media)
        [w h] (cond
                (and width height) [width height]
                (some? width)      [width (js/Math.round (* mh (/ width mw)))]
                (some? height)     [(js/Math.round (* mw (/ height mh))) height]
                :else              [mw mh])]
    {:x (or x 0) :y (or y 0) :width w :height h}))

(def ^:private media-error-hints
  {:ssrf-blocked-target
   "the host is private or blocked by this Penpot instance; use a public image URL"
   :unknown-size
   (str "the server sent no content-length header, which Penpot requires; "
        "picsum.photos, placehold.co and api.dicebear.com all send it")
   :media-type-not-allowed
   (str "the URL did not return a supported image type; for placehold.co "
        "request an explicit format, e.g. .../600x400.png")
   :media-max-file-size-reached
   "the image exceeds this instance's media size limit; request smaller dimensions"
   :unable-to-download-image
   "the image could not be downloaded (unreachable host, timeout, or error status)"})

(defn media-error-message
  "One-line agent-facing message for a media download/processing failure,
  naming the fix where one is known. Public for tests."
  [code]
  (str "insert_image: "
       (or (get media-error-hints code)
           (str "the upload failed" (when code (str " (" (name code) ")"))))
       "."))

(defn- insert-image
  [{:keys [url parentId] :as input}]
  (if-let [problem (insert-image-problem input)]
    (rx/throw (ex-info problem {}))
    (let [file-id (:current-file-id @st/state)
          nm      (or (:name input) "image")]
      (->> (rp/cmd! :create-file-media-object-from-url
                    {:name nm :file-id file-id :url url :is-local true})
           (rx/map
            (fn [media]
              (let [state   @st/state
                    page    (dsh/lookup-page state)
                    objects (:objects page)
                    pid     (some-> parentId parse-uuid)
                    parent? (boolean (and pid (contains? objects pid)))
                    geom    (image-geometry input media)
                    fills   (types.fills/create
                             {:fill-opacity 1
                              :fill-image {:width (:width media)
                                           :height (:height media)
                                           :mtype (:mtype media)
                                           :id (:id media)
                                           :keep-aspect-ratio true}})
                    shape   (cond-> (-> (cts/setup-shape
                                         (assoc geom :type :rect :name nm))
                                        (assoc :fills fills))
                              parent? (assoc :parent-id pid :frame-id pid))
                    changes (-> (cb/empty-changes)
                                (cb/with-page page)
                                (cb/with-objects objects)
                                (cb/add-object shape))]
                (interrupt!)
                (st/emit! (dch/commit-changes changes))
                (when parent?
                  (reflow-parent! objects pid))
                {:id (dm/str (:id shape))
                 :mediaId (dm/str (:id media))
                 :width (:width geom)
                 :height (:height geom)
                 :parentId (when parent? (dm/str pid))
                 :note "image inserted — verify geometry with read_design"})))
           (rx/catch
            (fn [cause]
              (rx/throw (ex-info (media-error-message (:code (ex-data cause)))
                                 {:cause-hint (ex-message cause)}))))))))

(defn shadow->shape
  "A shadow as Penpot stores it. `:color` is a *map* (`schema:color`), not a hex
  string — a bare string fails the schema."
  [{:keys [style offsetX offsetY blur spread color opacity]}]
  {:id (uuid/next)
   :style (if (= style "inner-shadow") :inner-shadow :drop-shadow)
   :offset-x (or offsetX 0)
   :offset-y (or offsetY 0)
   :blur (or blur 4)
   :spread (or spread 0)
   :hidden false
   :color (cond-> {:color (or color "#000000")}
            (some? opacity) (assoc :opacity opacity))})

(defn style-attrs
  "The plain shape attrs for the styling params. Only keys given are emitted, and
  presence is `some?` — `opacity 0` is a real instruction."
  [{:keys [radius opacity]}]
  (cond-> {}
    (some? radius) (assoc :r1 radius :r2 radius :r3 radius :r4 radius)
    (some? opacity) (assoc :opacity opacity)))

(defn- modify-shape
  [{:keys [shapeId x y width height fill stroke shadow] :as input}]
  (let [nm    (:name input)
        id    (some-> shapeId parse-uuid)
        state @st/state]
    (cond
      (nil? id)
      (rx/throw (ex-info "modify_shape: missing or invalid shapeId" {}))

      (some? (fill-problem fill))
      (rx/throw (ex-info (dm/str "modify_shape: " (fill-problem fill)) {}))

      ;; the guard runs last of the three: a rejection naming the rule is the
      ;; most useful message, so it should not mask a plain input error
      (some #(color-violation state %) (input-colors input))
      (rx/throw (some #(color-violation state %) (input-colors input)))

      :else
      (let [tx     (random-uuid)
            styles (style-attrs input)]
        (interrupt!)
        (st/emit! (dwu/start-undo-transaction tx))
        (when nm
          (st/emit! (dwsh/update-shapes [id] #(assoc % :name nm))))
        (when (seq styles)
          (st/emit! (dwsh/update-shapes [id] #(merge % styles))))
        (when shadow
          (st/emit! (dwsh/update-shapes [id] #(assoc % :shadow [(shadow->shape shadow)]))))
        (when (or (some? x) (some? y))
          (st/emit! (dwt/update-position id (cond-> {}
                                              (some? x) (assoc :x x)
                                              (some? y) (assoc :y y)))))
        (when (some? width)
          (st/emit! (dwt/update-dimensions [id] :width width)))
        (when (some? height)
          (st/emit! (dwt/update-dimensions [id] :height height)))
        (when fill
          (st/emit! (dwsh/update-shapes [id] #(assoc % :fills (fill->shape fill)))))
        (when stroke
          (st/emit! (dwsh/update-shapes [id] #(assoc % :strokes [{:stroke-color stroke
                                                                  :stroke-opacity 1
                                                                  :stroke-width 1
                                                                  :stroke-style :solid
                                                                  :stroke-alignment :center}]))))
        (st/emit! (dwu/commit-undo-transaction tx))
        (rx/of {:id shapeId :note "modified — verify with read_design"})))))

(defn- nest-shape
  [{:keys [shapeId parentId index]}]
  (let [id  (some-> shapeId parse-uuid)
        pid (some-> parentId parse-uuid)]
    (if (and id pid)
      (do (interrupt!)
          (st/emit! (dwsh/relocate-shapes #{id} pid (or index 0)))
          (rx/of {:id shapeId :parentId parentId
                  :note "reparented — verify with read_design"}))
      (rx/throw (ex-info "nest_shape: missing or invalid shapeId/parentId" {})))))

;; --- Text & component tools

(defn- create-text
  [{:keys [text x y parentId fill align] :as input}]
  (if-let [problem (or (when (or (not (string? text)) (empty? text))
                         "create_text: text must be a non-empty string")
                       (enum-problem "create_text" "align" align text-aligns))]
    (rx/throw (ex-info problem {}))
    (let [nm      (:name input)
          color   (or fill "#000000")
          state   @st/state
          page    (dsh/lookup-page state)
          objects (:objects page)
          pid     (some-> parentId parse-uuid)
          parent? (boolean (and pid (contains? objects pid)))
          shape   (-> (cts/setup-shape
                       (cond-> {:type :text
                                :x (or x 0) :y (or y 0)
                                :width 1 :height 1
                                :grow-type :auto-width}
                         nm (assoc :name nm)))
                      (update :content txt/change-text text
                              (cond-> {:fills [{:fill-color color :fill-opacity 1}]}
                                ;; creating right beats creating then fixing
                                align (assoc :text-align align)))
                      (dissoc :position-data)
                      (cond-> parent? (assoc :parent-id pid :frame-id pid)))
          changes (-> (cb/empty-changes)
                      (cb/with-page page)
                      (cb/with-objects objects)
                      (cb/add-object shape))]
      (interrupt!)
      (st/emit! (dch/commit-changes changes))
      (when (features/active-feature? @st/state "render-wasm/v1")
        (st/emit! (dwwt/resize-wasm-text-debounce (:id shape))))
      (rx/of {:id (dm/str (:id shape))
              :note "text created — size settles async; verify with read_design"}))))

;; --- Text
;;
;; Scope came from the phase's own instruction to check the token path first, and
;; it narrowed the phase sharply: `apply_tokens` ALREADY reaches fontSize,
;; fontFamily, fontWeight, letterSpacing, lineHeight, textCase and
;; textDecoration (Phase 09 derived them from `cto/all-keys`). Verified live — a
;; `fontSizes` token applied to a text really does resize it. So
;; `penpot-foundations`' type scale already works, and the premise "every text is
;; the same default-sized paragraph" was only half true.
;;
;; What is genuinely unreachable: the WORDS (no path at all) and ALIGNMENT (not a
;; token attr — there is no text-align token). This covers exactly those, and
;; points at apply_tokens for the rest, the same relationship `fill` has with
;; colour tokens.

(defn text-problem
  "Why `set_text` cannot run, or nil. Pure."
  [objects id {:keys [text align]}]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "set_text: shapeId is required"

      (nil? shape)
      (dm/str "set_text: no shape on this page with id " (str id) " — check read_design")

      (not (cfh/text-shape? shape))
      (dm/str "set_text: " (shape-label shape) " is a "
              (some-> (:type shape) name)
              ", not a text shape — note modify_shape's `name` renames the LAYER,"
              " it does not change the words; create text with create_text")

      ;; `some?`, not truthiness: "" clears the text, which is a real edit
      (and (nil? text) (nil? align))
      "set_text: nothing to change — pass text (the words) and/or align"

      :else
      (enum-problem "set_text" "align" align text-aligns))))

(defn- set-text
  [{:keys [shapeId text align]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (text-problem objects id {:text text :align align})]
      (rx/throw (ex-info problem {}))
      (do
        (interrupt!)
        (when (some? text)
          ;; the same pure content transform create_text seeds with, so it keeps
          ;; the first paragraph's styling rather than resetting it
          (st/emit! (dwsh/update-shapes [id] #(update % :content txt/change-text text))))
        (when align
          ;; update-paragraph-attrs has a headless branch: with no editor open it
          ;; falls through to update-shapes. The agent never has an editor.
          (st/emit! (dwt-text/update-paragraph-attrs {:id id :attrs {:text-align align}})))
        (when (features/active-feature? @st/state "render-wasm/v1")
          (st/emit! (dwwt/resize-wasm-text-debounce id)))
        (rx/of {:id shapeId
                :note (str "text updated — size settles async. Font size, family and "
                           "weight are design tokens: author one with create_token "
                           "(fontSizes) and bind it with apply_tokens rather than "
                           "hardcoding. Verify with read_design.")})))))

(defn- create-component
  [{:keys [shapeIds]}]
  (let [ids (if (seq shapeIds)
              (into #{} (keep parse-uuid) shapeIds)
              (dsh/get-selected-ids @st/state))]
    (if (empty? ids)
      (rx/throw (ex-info "create_component: no shapes given and nothing selected" {}))
      (try
        (let [id-ref (atom nil)]
          (interrupt!)
          (st/emit! (dwl/add-component id-ref ids))
          (if-let [cid (deref id-ref)]
            (rx/of {:componentId (dm/str cid) :note "component created"})
            (rx/throw (ex-info "create_component: shapes are not eligible for a component" {}))))
        (catch :default e
          (rx/throw e))))))



;; --- Delete / duplicate
;;
;; `create_shape` shipped without a counterpart, so the agent could make a mess
;; and not clean it up. Both events filter silently — `delete-shapes` asserts a
;; set, and `duplicate-shapes` drops anything `allow-duplicate?` refuses and then
;; no-ops on the empty set, which reads as success.

(defn- ids-problem
  [tool objects ids]
  (let [missing (remove #(contains? objects %) ids)]
    (cond
      (empty? ids)
      (dm/str tool ": shapeIds is required — pass the ids to act on (see read_design)")

      (seq missing)
      (dm/str tool ": no shape on this page with id "
              (str/join ", " (map str missing))
              " — it may be on another page, or already gone; check read_design"))))

(defn delete-problem
  "Why `delete_shape` cannot run, or nil. Pure."
  [objects ids]
  (ids-problem "delete_shape" objects ids))

(defn duplicate-problem
  "Why `duplicate_shape` cannot run, or nil. Pure."
  [objects ids]
  (or (ids-problem "duplicate_shape" objects ids)
      (let [blocked (remove #(ctc/allow-duplicate? objects (get objects %)) ids)]
        (when (seq blocked)
          (dm/str "duplicate_shape: " (labels (map #(get objects %) blocked))
                  (if (= 1 (count blocked)) " is" " are")
                  " inside a component copy, whose structure is owned by the main"
                  " component — duplicate the main instead, or sever this copy with"
                  " detach_instance first")))))

(defn- delete-shape
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (delete-problem objects ids)]
      (rx/throw (ex-info problem {}))
      (do
        (interrupt!)
        ;; delete-shapes asserts a set; combine-as-variants wanted a vector.
        ;; Two conventions live in this file — convert at the boundary.
        (st/emit! (dwsh/delete-shapes (set ids)))
        (rx/of {:deleted (count ids)
                :note "deleted — undo with ⌘Z (it is one undo step)"})))))

(defn- duplicate-shape
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (duplicate-problem objects ids)]
      (rx/throw (ex-info problem {}))
      (let [id-ref (atom nil)]
        (interrupt!)
        (st/emit! (dws/duplicate-shapes (set ids)
                                        ;; offset like ⌘D: a copy exactly on top of
                                        ;; its original is invisible to the agent
                                        :move-delta? true
                                        ;; never move the user's selection
                                        :change-selection? false
                                        :return-ref id-ref))
        ;; `return-ref` gets ONE id — the representative duplicate — not a
        ;; collection, and it is reset from an `rx/tap` on the emitted stream, so
        ;; it can still be nil here. Report it only when it is both present and
        ;; unambiguous; never guess an id.
        (let [new-id (deref id-ref)]
          (rx/of (cond-> {:duplicated (count ids)
                          :note (str "duplicated. Penpot places a duplicated BOARD to "
                                     "the right of its original, but leaves every other "
                                     "shape exactly on top of the one it copied — so "
                                     "unless you duplicated a board, move it with "
                                     "modify_shape or nest it with nest_shape, or it is "
                                     "invisible. Verify with read_design.")}
                   (and new-id (= 1 (count ids)))
                   (assoc :shapeId (dm/str new-id)))))))))

;; --- Group / ungroup
;;
;; Both events mirror the pattern this file exists to close: they filter their
;; input silently and then `(when-not (empty? …))`, so a fully-filtered call
;; does nothing and returns success.

(defn group-problem
  "Why `group_shapes` cannot run, or nil. Mirrors `group-shapes`' own filters
  (`groups.cljs:195-200`)."
  [objects ids]
  (or (ids-problem "group_shapes" objects ids)
      (let [shapes   (map #(get objects %) ids)
            variants (filter ctc/is-variant? shapes)
            in-copy  (filter #(ctn/has-any-copy-parent? objects %) shapes)]
        (cond
          (seq variants)
          (dm/str "group_shapes: " (labels variants)
                  (if (= 1 (count variants)) " is" " are")
                  " part of a variant set, which owns its own structure"
                  " — a variant set cannot be grouped")

          (seq in-copy)
          (dm/str "group_shapes: " (labels in-copy)
                  (if (= 1 (count in-copy)) " is" " are")
                  " inside a component copy, whose structure is owned by the main"
                  " component — group the main instead, or sever this copy with"
                  " detach_instance first")))))

(defn ungroup-problem
  "Why `ungroup_shapes` cannot run, or nil. Mirrors `ungroup-shapes`' filters
  (`groups.cljs:242-246`) and what its `prepare` can actually handle."
  [objects ids]
  (or (ids-problem "ungroup_shapes" objects ids)
      (let [shapes    (map #(get objects %) ids)
            in-copy   (filter #(ctn/has-any-copy-parent? objects %) shapes)
            comps     (filter ctc/instance-head? shapes)
            containers (filter ctc/is-variant-container? shapes)
            ;; prepare handles group / bool / frame; anything else yields no
            ;; changes and is dropped without a word
            wrong     (remove #(or (cfh/group-shape? %)
                                   (cfh/bool-shape? %)
                                   (cfh/frame-shape? %))
                              shapes)]
        (cond
          (seq containers)
          (dm/str "ungroup_shapes: " (labels containers)
                  (if (= 1 (count containers)) " is a variant container" " are variant containers")
                  " — ungrouping one would destroy the set; delete it instead if that is the intent")

          (seq comps)
          (dm/str "ungroup_shapes: " (labels comps)
                  (if (= 1 (count comps)) " is a component" " are components")
                  " and components cannot be ungrouped — sever a copy with detach_instance,"
                  " or delete the component")

          (seq in-copy)
          (dm/str "ungroup_shapes: " (labels in-copy)
                  (if (= 1 (count in-copy)) " is" " are")
                  " inside a component copy, whose structure is owned by the main component")

          (seq wrong)
          (dm/str "ungroup_shapes: " (labels wrong)
                  (if (= 1 (count wrong)) " is a " " are ")
                  (some-> (:type (first wrong)) name)
                  " — only a group, a board or a boolean can be ungrouped")))))

(defn- group-shapes
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (group-problem objects ids)]
      (rx/throw (ex-info problem {}))
      ;; group-shapes takes the new group's id, so it is knowable up front
      (let [group-id (uuid/next)]
        (interrupt!)
        (st/emit! (dwg/group-shapes group-id (into #{} ids)))
        (rx/of {:groupId (dm/str group-id)
                :note (str "grouped. A group is not a board: it has no layout, fill "
                           "or clip — if you want these arranged, use a board with "
                           "set_layout instead. Verify with read_design.")})))))

(defn- ungroup-shapes
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (into [] (comp (keep parse-uuid) (distinct)) shapeIds)]
    (if-let [problem (ungroup-problem objects ids)]
      (rx/throw (ex-info problem {}))
      (do
        (interrupt!)
        (st/emit! (dwg/ungroup-shapes (into #{} ids)))
        (rx/of {:note "ungrouped — the children stay where they were. Verify with read_design."})))))

;; --- Layout (flex)
;;
;; The single widest gap in the tool surface: the skills' central method is
;; "create a board, give it a flex layout, apply spacing tokens to the gaps", and
;; only the first step was reachable — which is why the agent positioned
;; everything by absolute x/y.
;;
;; Names and values are mapped from `common/types/shape/layout.cljc`, NOT from
;; the plugin's `flex.cljs`: the plugin proxy does its own aliasing, and copying
;; its public names without its translation layer would silently miss.

(def ^:private layout-dirs #{"row" "row-reverse" "column" "column-reverse"})
(def ^:private layout-align-items #{"start" "end" "center" "stretch"})
(def ^:private layout-justify-content
  #{"start" "center" "end" "space-between" "space-around" "space-evenly" "stretch"})

(defn layout-changes
  "The `:layout-*` patch for `update-layout`, from the tool's public params.

  Only keys actually given are emitted — `update-layout` patches whatever it
  receives, so a stray nil would clobber a value the caller never mentioned."
  [{:keys [dir alignItems justifyContent rowGap columnGap padding wrap]}]
  (let [gap (cond-> {}
              (some? rowGap)    (assoc :row-gap rowGap)
              (some? columnGap) (assoc :column-gap columnGap))
        pad (cond-> {}
              (some? (:top padding))    (assoc :p1 (:top padding))
              (some? (:right padding))  (assoc :p2 (:right padding))
              (some? (:bottom padding)) (assoc :p3 (:bottom padding))
              (some? (:left padding))   (assoc :p4 (:left padding)))]
    (cond-> {}
      (some? dir)            (assoc :layout-flex-dir (keyword dir))
      (some? alignItems)     (assoc :layout-align-items (keyword alignItems))
      (some? justifyContent) (assoc :layout-justify-content (keyword justifyContent))
      (some? wrap)           (assoc :layout-wrap-type (if wrap :wrap :nowrap))
      (seq gap)              (assoc :layout-gap gap)
      (seq pad)              (assoc :layout-padding pad))))


(def ^:private layout-kinds #{"flex" "grid"})

(defn grid-tracks
  "N equal free-space columns, each Penpot's `default-track-value` (`{:type :flex
  :value 1}` — a `1fr` track). Nil/0 columns means \"let calculate-params infer
  the tracks from the children\", which is the auto-grid's whole point, so it
  adds none."
  [columns]
  (if (and (number? columns) (pos? columns))
    (vec (repeat columns {:type :flex :value 1}))
    []))

(defn rebuild-grid
  "Force a grid to N columns and flow its children across them in append order.

  `create-layout-from-id`'s auto-grid infers tracks from child *positions*, so
  near-aligned children collapse into a single column — `columns: N` must mean
  N columns regardless of where the children happen to sit. Resets the tracks and
  cells (like `calculate-params`' empty-children branch, but for a populated
  board) and re-assigns."
  [shape objects n]
  (let [n    (max 1 (int n))
        kids (count (:shapes shape))
        rows (max 1 (js/Math.ceil (/ kids n)))]
    (-> shape
        (assoc :layout-grid-columns (vec (repeat n ctl/default-track-value))
               :layout-grid-rows    (vec (repeat rows ctl/default-track-value))
               :layout-grid-cells   {})
        (ctl/create-cells [1 1 n rows])
        (ctl/assign-cells objects)
        (ctl/reorder-grid-children))))

(defn layout-problem
  "Why `set_layout` cannot be applied to `id`, as a message the agent can act on,
  or nil. Pure."
  [objects id {:keys [type dir alignItems justifyContent remove] :as input}]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "set_layout: shapeId is required"

      (nil? shape)
      (dm/str "set_layout: no shape on this page with id " (str id)
              " — check read_design")

      (not (cfh/frame-shape? shape))
      (dm/str "set_layout: " (shape-label shape) " is a "
              (some-> (:type shape) name)
              ", and only boards can have a layout — create one with"
              " create_shape type=board and nest these shapes into it")

      (and remove (nil? (:layout shape)))
      (dm/str "set_layout: " (shape-label shape) " has no layout to remove")

      :else
      (or (enum-problem "set_layout" "type" type layout-kinds)
          (enum-problem "set_layout" "dir" dir layout-dirs)
          (enum-problem "set_layout" "alignItems" alignItems layout-align-items)
          (enum-problem "set_layout" "justifyContent" justifyContent layout-justify-content)
          ;; `type` is itself a change ("make this a grid"), so it satisfies the
          ;; nothing-to-change guard on its own
          (when (and (not remove) (nil? type) (empty? (layout-changes input)))
            (dm/str "set_layout: nothing to change — pass type (flex/grid), dir,"
                    " alignItems, justifyContent, rowGap, columnGap, padding or"
                    " wrap (or remove: true)"))))))

(defn- set-layout
  [{:keys [shapeId type columns remove] :as input}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (layout-problem objects id input)]
      (rx/throw (ex-info problem {}))
      (let [shape    (get objects id)
            changes  (layout-changes input)
            existing (:layout shape)
            ;; default to flex, as before; honour an explicit type; a board that
            ;; already has a layout keeps its kind unless type says otherwise
            kind     (cond type (keyword type)
                           existing existing
                           :else :flex)
            grid?    (= :grid kind)
            tracks   (grid-tracks columns)]
        (interrupt!)
        (cond
          remove
          (do (st/emit! (dwsl/remove-layout #{id}))
              (rx/of {:note "layout removed — children keep their positions"}))

          :else
          (do
            ;; (re)create when there is no layout, or when switching kinds —
            ;; create-layout-from-id seeds the right initializer for each
            (when (or (nil? existing) (not= existing kind))
              (st/emit! (dwsl/create-layout-from-id id kind)))
            (when (seq changes)
              (st/emit! (dwsl/update-layout [id] changes)))
            ;; explicit columns: rebuild the grid so children actually flow
            ;; across N columns, not just widen the track vector under cells the
            ;; auto-grid already stacked into column 1. {:with-objects? true}
            ;; gives the update fn the fresh objects assign-cells needs.
            (when (and grid? (seq tracks))
              (st/emit! (dwsh/update-shapes [id]
                                            (fn [shape objs] (rebuild-grid shape objs columns))
                                            {:with-objects? true})))
            (rx/of {:note (if grid?
                            (str "grid layout applied — children flow into cells and "
                                 "reflow as you add more, so stop setting their x/y. "
                                 "Gaps and padding accept spacing tokens via "
                                 "apply_tokens. Verify with read_design.")
                            (str "flex layout applied — children now reflow, so stop "
                                 "setting their x/y. Gaps and padding accept spacing "
                                 "tokens via apply_tokens. Verify with read_design."))})))))))

(def ^:private layout-sizing #{"fill" "fix" "auto"})
(def ^:private layout-align-self #{"start" "end" "center" "stretch"})

(defn layout-child-attrs
  "The `:layout-item-*` patch for `update-layout-child`, from the tool's public
  params. Only keys actually given are emitted — but note `absolute false` is a
  real instruction (\"rejoin the flow\"), so presence is tested with `some?`
  rather than truthiness."
  [{:keys [horizontalSizing verticalSizing alignSelf margin absolute zIndex
           minWidth maxWidth minHeight maxHeight]}]
  (let [m (cond-> {}
            (some? (:top margin))    (assoc :m1 (:top margin))
            (some? (:right margin))  (assoc :m2 (:right margin))
            (some? (:bottom margin)) (assoc :m3 (:bottom margin))
            (some? (:left margin))   (assoc :m4 (:left margin)))]
    (cond-> {}
      (some? horizontalSizing) (assoc :layout-item-h-sizing (keyword horizontalSizing))
      (some? verticalSizing)   (assoc :layout-item-v-sizing (keyword verticalSizing))
      (some? alignSelf)        (assoc :layout-item-align-self (keyword alignSelf))
      (some? absolute)         (assoc :layout-item-absolute absolute)
      (some? zIndex)           (assoc :layout-item-z-index zIndex)
      (some? minWidth)         (assoc :layout-item-min-w minWidth)
      (some? maxWidth)         (assoc :layout-item-max-w maxWidth)
      (some? minHeight)        (assoc :layout-item-min-h minHeight)
      (some? maxHeight)        (assoc :layout-item-max-h maxHeight)
      (seq m)                  (assoc :layout-item-margin m))))

(defn layout-child-problem
  "Why `set_layout_child` cannot be applied to `id`, or nil. Pure.

  The parent-has-no-layout branch is why this is a separate tool rather than part
  of `modify_shape`: `update-layout-child` writes `:layout-item-*` onto any shape
  quite happily. The attrs persist, do nothing, and spring to life the moment
  someone adds a layout later — a silent no-op wearing a success message."
  [objects id {:keys [horizontalSizing verticalSizing alignSelf] :as input}]
  (let [shape  (get objects id)
        parent (some->> (:parent-id shape) (get objects))]
    (cond
      (nil? id)
      "set_layout_child: shapeId is required"

      (nil? shape)
      (dm/str "set_layout_child: no shape on this page with id " (str id)
              " — check read_design")

      (or (nil? parent) (= uuid/zero (:parent-id shape)))
      (dm/str "set_layout_child: " (shape-label shape) " is not inside a board"
              " — nest it with nest_shape first")

      (not (ctl/any-layout? parent))
      (dm/str "set_layout_child: the parent of " (shape-label shape)
              " has no layout, so these settings would be stored and do nothing"
              " — call set_layout on board " (dm/str (:parent-id shape)) " first")

      :else
      (or (enum-problem "set_layout_child" "horizontalSizing" horizontalSizing layout-sizing)
          (enum-problem "set_layout_child" "verticalSizing" verticalSizing layout-sizing)
          (enum-problem "set_layout_child" "alignSelf" alignSelf layout-align-self)
          (when (empty? (layout-child-attrs input))
            (dm/str "set_layout_child: nothing to change — pass horizontalSizing,"
                    " verticalSizing, alignSelf, margin, absolute, zIndex or"
                    " min/max width/height"))))))

(defn- set-layout-child
  [{:keys [shapeId] :as input}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (layout-child-problem objects id input)]
      (rx/throw (ex-info problem {}))
      (do
        (interrupt!)
        (st/emit! (dwsl/update-layout-child [id] (layout-child-attrs input)))
        (rx/of {:note (str "layout applied to the child — its parent reflows. "
                           "Margins accept spacing tokens via apply_tokens. "
                           "Verify with read_design.")})))))

;; --- Variants
;;
;; `dwv/combine-as-variants` filters out ids that are not main instances or are
;; already variants, and no-ops entirely below two ids — all silently. Silence
;; is the one answer the agent cannot act on: with no signal it improvises
;; something that resembles the capability (frames named `Card=Size=Compact`).
;; So the eligibility rules are checked here first, mirroring the event's own
;; filter, and rejected with a message that names the corrective action.


(defn variant-member-ids
  "The parsed, de-duplicated member ids from a `create_variant` input, in the
  order given — `combine-as-variants` honours the order of a sequential
  collection and normalizes anything else to layer-tree order."
  [shapeIds]
  (into [] (comp (keep parse-uuid) (distinct)) shapeIds))

(defn variant-naming-hint
  "Nil when `names` share a path prefix; otherwise a note explaining what the
  agent is about to get and how to ask for better.

  `combine-as-variants` derives the set's name from the common *path* prefix of
  its members (`variants.cljs:667`). With no shared prefix, `transform-in-variant`
  falls back to `\"Component/\" + name` (`:413`), so the set is called
  \"Component\" and the member's own name becomes the first property's value.
  Path depth also decides how many axes the set gets — `num-props` is
  `(max 1 (dec (count cpath)))` at `:426`.

  So `Badge / Compact` + `Badge / Large` gives a set named \"Badge\" with one
  axis, and `Chip / Small / Hover` gives \"Chip\" with two. The operation is
  valid either way — this is a hint, not a rejection."
  [names]
  (let [paths  (mapv #(cpn/split-path (or % "")) names)
        shared (->> (apply map vector paths)
                    (take-while #(apply = %))
                    (map first)
                    (vec))]
    (when (empty? shared)
      (dm/str "note: these components share no common path, so Penpot named the set"
              " \"Component\" and used each member's own name as its first property"
              " value. To get a named set, name the components with a shared path"
              " first — e.g. \"Badge / Compact\" and \"Badge / Large\" produce a set"
              " called \"Badge\". Each extra path segment adds another property:"
              " \"Chip / Small / Hover\" gives two."))))

(defn variant-members-problem
  "Why `ids` cannot become a variant set, as a message the agent can act on, or
  nil when they can. Pure: `objects` is the current page's shape map."
  [objects ids]
  ;; distinct first: [a a] reaches combine-as-variants as one id and no-ops
  ;; silently, so it must not read as two members here
  (let [ids      (distinct ids)
        shapes   (keep #(get objects %) ids)
        missing  (remove #(contains? objects %) ids)
        ;; a variant member is also a main instance, so this branch is checked
        ;; first — "extend the set you already have" beats "make a component"
        variants (filter ctc/is-variant? shapes)
        non-main (remove ctc/main-instance? shapes)]
    (cond
      (< (count ids) 2)
      (dm/str "create_variant: needs at least 2 main components (got " (count ids)
              ") — a variant set is a comparison between members")

      (seq missing)
      (dm/str "create_variant: no shape on this page with id "
              (str/join ", " (map str missing))
              " — it may be on another page, or gone; check read_design")

      (seq variants)
      (dm/str "create_variant: " (labels variants)
              (if (= 1 (count variants)) " is" " are")
              " already part of a variant set — use add_variant to extend it")

      (seq non-main)
      (dm/str "create_variant: " (labels non-main)
              (if (= 1 (count non-main))
                " is not a main component — call create_component on it first"
                " are not main components — call create_component on them first")
              ", then pass the main instance")

      :else nil)))

(defn- create-variant
  [{:keys [shapeIds]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (if (seq shapeIds)
                  (variant-member-ids shapeIds)
                  (vec (dsh/get-selected-ids state)))]
    (if-let [problem (variant-members-problem objects ids)]
      (rx/throw (ex-info problem {}))
      ;; the container's id *is* the variant-id, so pre-generating it lets us
      ;; return the id without awaiting the event chain (as api.cljs does)
      (let [variant-id (uuid/next)
            hint       (variant-naming-hint (map #(:name (get objects %)) ids))]
        (interrupt!)
        (st/emit! (dwv/combine-as-variants
                   ids {:trigger "agent:create_variant" :variant-id variant-id}))
        (rx/of (cond-> {:variantId (dm/str variant-id)
                        :members (count ids)
                        :note (str "variant set created — its name and properties come from "
                                   "the members' names. Axes are called \"Property 1\"…; "
                                   "name them with set_variant_property. Verify with read_design.")}
                 hint (assoc :namingHint hint)))))))

(defn add-variant-problem
  "Why `add_variant` cannot grow this set, as a message the agent can act on, or
  nil. Accepts either a member or the container: `add-new-variant` resolves a
  container to its primary variant (`variants.cljs:360`)."
  [objects id]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      (dm/str "add_variant: shapeId is required — pass a variant set's container"
              " or one of its members (see read_design)")

      (nil? shape)
      (dm/str "add_variant: no shape on this page with id " (str id)
              " — it may be on another page, or gone; check read_design")

      (or (ctc/is-variant-container? shape) (ctc/is-variant? shape))
      nil

      :else
      (dm/str "add_variant: " (shape-label shape) " is not part of a variant set"
              " — a set needs at least two main components; build one with"
              " create_variant"))))

(defn- add-variant
  [{:keys [shapeId]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (add-variant-problem objects id)]
      (rx/throw (ex-info problem {}))
      ;; add-new-variant generates the new ids internally and exposes no id-ref,
      ;; but it ends by selecting the new shape — so the selection is the only
      ;; handle on it. Compare against the previous selection rather than trust
      ;; it: an unchanged selection means we have no id to report, not a wrong one.
      (let [before (dsh/get-selected-ids state)]
        (interrupt!)
        (st/emit! (dwv/add-new-variant id))
        (let [after (dsh/get-selected-ids @st/state)
              new-id (first (remove before after))]
          (rx/of (cond-> {:note (str "variant added — it starts with a placeholder value "
                                     "(\"Value N\"); give it a real one with "
                                     "set_variant_property. Verify with read_design.")}
                   new-id (assoc :shapeId (dm/str new-id)))))))))

;; --- Handoff codegen
;;
;; The whole method of `penpot-design-to-code-review`: read what Penpot says this
;; design IS, compare it to the code that shipped. Its own script calls
;; generateMarkup AND generateStyle together and returns `{markup, style, …}`, so
;; this is one tool returning both rather than two the agent must pair up.
;;
;; Comparison is why size matters here more than elsewhere: a half stylesheet
;; does not degrade the review, it produces confident findings about rules that
;; were never truncated in reality. Phase 15's backstop refuses oversized results
;; outright, so the failure is loud — this tool just narrows the ask first.

(def ^:private markup-types #{"html" "svg"})

(defn code-problem
  "Why `generate_code` cannot run, or nil. Pure."
  [objects ids type]
  (or (when (empty? ids)
        (str "generate_code: nothing to inspect — pass shapeIds, or select the"
             " shapes you want the markup for"))
      (ids-problem "generate_code" objects ids)
      (when (and (some? type) (not (contains? markup-types type)))
        (dm/str "generate_code: \"" type "\" is not a markup type — use one of: "
                (str/join ", " (sort markup-types))))))

(defn- generate-code
  [{:keys [shapeIds type includeChildren]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        ids     (if (seq shapeIds)
                  (into [] (comp (keep parse-uuid) (distinct)) shapeIds)
                  (vec (dsh/get-selected-ids state)))
        type    (or type "html")]
    (if-let [problem (code-problem objects ids type)]
      (rx/throw (ex-info problem {}))
      (let [roots    (mapv #(get objects %) ids)
            ;; children? defaults true, as the plugin's does — a component's own
            ;; styles without its children is rarely what a review wants
            kids?    (not (false? includeChildren))
            resolved (cond->> (cfh/clean-loops objects roots)
                       kids? (mapcat #(cfh/get-children-with-self objects (:id %))))
            markup   (cg/generate-formatted-markup-code objects type resolved)
            style    (cg/generate-style-code objects "css" roots resolved nil)]
        (rx/of {:markup markup
                :style style
                :shapes (count resolved)
                :note (str "Penpot's own view of these shapes — compare it against "
                           "the shipped code rather than reading the canvas. If the "
                           "result is refused as too large, inspect fewer shapes or "
                           "pass includeChildren: false.")})))))

;; --- Component instances
;;
;; Without this a design system the agent builds is write-only: it can author a
;; component and never place one, so it redraws from primitives every time and
;; the library it just made goes unused.

(defn instance-problem
  "Why `create_instance` cannot place this component, or nil. Pure.

  `instantiate-component` asserts on `file-id`, `component-id` and
  `(gpt/point? position)` — an assert throws rather than returning a message the
  agent can read, so everything is checked here first."
  [libraries current-file-id {:keys [componentId fileId x y]}]
  (let [file-id (or (some-> fileId parse-uuid) current-file-id)
        cid     (some-> componentId parse-uuid)
        found   (some-> (get-in libraries [file-id :data])
                        (ctkl/components)
                        (get cid))]
    (cond
      (nil? cid)
      "create_instance: componentId is required — see the components in read_design"

      ;; not truthiness: 0 is a legitimate coordinate
      (or (nil? x) (nil? y))
      "create_instance: x and y are required — a component is placed at a point"

      (nil? found)
      (dm/str "create_instance: no component " componentId
              (if fileId
                (dm/str " in library " fileId)
                " in this file")
              " — check the components listed by read_design"
              (when-not fileId
                ", and pass fileId if it belongs to a connected library")))))

(defn- create-instance
  [{:keys [componentId fileId x y] :as input}]
  (let [state     @st/state
        libraries (dsh/lookup-libraries state)
        cur-id    (:current-file-id state)]
    (if-let [problem (instance-problem libraries cur-id input)]
      (rx/throw (ex-info problem {}))
      (let [id-ref (atom nil)]
        (interrupt!)
        (st/emit! (dwl/instantiate-component (or (some-> fileId parse-uuid) cur-id)
                                             (parse-uuid componentId)
                                             (gpt/point x y)
                                             {:id-ref id-ref
                                              :origin "agent:create_instance"}))
        (let [new-id (deref id-ref)]
          (rx/of (cond-> {:note (str "instance placed — it stays linked to its main, "
                                     "so editing the main updates it. Verify with "
                                     "read_design.")}
                   new-id (assoc :shapeId (dm/str new-id)))))))))

;; --- Detach
;;
;; The missing half of Wave 5, and the plan's standing rule pointed at itself:
;; `duplicate_shape` and `group_shapes` already reject with "…or detach the copy
;; first", naming a tool that did not exist.
;;
;; Gotcha #12 — detaching a variant instance "has corrupted files and hung all
;; subsequent saves" — is about the PLUGIN path. Tested natively on a scratch
;; file: revn advanced 97→100, later edits persisted, the file reloaded clean.
;; So the variant guard here refuses for the honest reason (a member is the set's
;; structure) rather than repeating a corruption claim we could not reproduce.

(defn detach-problem
  "Why `detach_instance` cannot run, or nil. Pure."
  [objects id]
  (let [shape (get objects id)]
    (cond
      (nil? id)
      "detach_instance: shapeId is required"

      (nil? shape)
      (dm/str "detach_instance: no shape on this page with id " (str id)
              " — check read_design")

      (ctc/is-variant? shape)
      (dm/str "detach_instance: " (shape-label shape) " is a member of a variant set"
              " — a member is the set's structure, so detaching it would gut the set."
              " Detach a COPY of it instead, or delete the member if you meant to"
              " shrink the set")

      (ctc/main-instance? shape)
      (dm/str "detach_instance: " (shape-label shape) " is a component's MAIN"
              " — it is the component, so there is nothing to detach it from. You"
              " probably meant one of its copies; place one with create_instance,"
              " or edit the main directly")

      (not (ctc/in-component-copy? shape))
      (dm/str "detach_instance: " (shape-label shape) " is not a component copy"
              " — it has no main to be severed from")

      (not (ctc/instance-root? shape))
      (let [root (ctn/get-instance-root objects shape)]
        (dm/str "detach_instance: " (shape-label shape) " is a piece of a copy,"
                " not the copy itself — detach its root instead"
                (when root (dm/str ": " (dm/str (:id root)))))))))

(defn- detach-instance
  [{:keys [shapeId]}]
  (let [state   @st/state
        objects (dsh/lookup-page-objects state)
        id      (some-> shapeId parse-uuid)]
    (if-let [problem (detach-problem objects id)]
      (rx/throw (ex-info problem {}))
      (do
        (interrupt!)
        (st/emit! (dwl/detach-component id))
        (rx/of {:shapeId shapeId
                :note (str "detached — these shapes are now local copies and will "
                           "NOT follow their main any more. Tell the user you did "
                           "this; it is not reversible by editing the main.")})))))

;; --- Variant properties
;;
;; `dwv/update-property-name` guards on `valid-pos?` and does nothing when the
;; index is out of range (variants.cljs:131) — another silent no-op. It reads the
;; property list from the *last* of `find-variant-components`, so positions are
;; resolved against that same component: a pos we resolve is a pos it accepts.

(defn axis-pos
  "The index of the axis named `property` among `axes`, or nil.

  Callers must resolve this *before* emitting a rename — the index is stable but
  the name is what we look up, so resolving afterwards finds nothing."
  [axes property]
  (first (keep-indexed (fn [i n] (when (= n property) i)) axes)))

(defn variant-property-problem
  "Why a `set_variant_property` call cannot be applied, as a message the agent
  can act on, or nil. Pure: `axes` are the set's property names in order,
  `member-ids` its component ids as strings."
  [{:keys [container? axes member-ids]} {:keys [variantId property rename componentId value]}]
  (cond
    (not container?)
    (dm/str "set_variant_property: " variantId " is not a variant container"
            " — build one with create_variant, or see read_design for the sets that exist")

    (nil? (axis-pos axes property))
    (dm/str "set_variant_property: no axis named \"" property "\" on this set"
            " (it has: " (str/join ", " axes) ") — pass one of those")

    (and (nil? rename) (nil? value))
    (dm/str "set_variant_property: nothing to do — pass rename to name the axis,"
            " and/or value together with componentId to set one member's value")

    (and (some? value) (nil? componentId))
    (dm/str "set_variant_property: setting a value needs componentId — a value"
            " belongs to one member, not the whole set")

    (and (some? componentId) (not (contains? member-ids componentId)))
    (dm/str "set_variant_property: component " componentId " is not a member of"
            " variant set " variantId " — see read_design")

    :else nil))

(defn- variant-facts
  "The set's shape as `variant-property-problem` wants it. Axes come from the
  same component `update-property-name` reads, so a resolved pos is an accepted
  pos."
  [data objects variant-id]
  (let [components (cfv/find-variant-components data objects variant-id)]
    {:container? (ctc/is-variant-container? (get objects variant-id))
     :axes (mapv :name (:variant-properties (last components)))
     :member-ids (into #{} (comp (remove nil?) (map #(str (:id %)))) components)}))

(defn- set-variant-property
  [{:keys [variantId property rename componentId value] :as input}]
  (let [state   @st/state
        data    (dsh/lookup-file-data state)
        objects (dsh/lookup-page-objects state)
        vid     (parse-uuid variantId)
        facts   (if vid
                  (variant-facts data objects vid)
                  {:container? false :axes [] :member-ids #{}})]
    (if-let [problem (variant-property-problem facts input)]
      (rx/throw (ex-info problem {}))
      ;; resolve the index from the current name before any rename lands
      (let [pos (axis-pos (:axes facts) property)]
        (interrupt!)
        (when rename
          (st/emit! (dwv/update-property-name vid pos rename
                                              {:trigger "agent:set_variant_property"})))
        (when (and componentId value)
          (st/emit! (dwv/update-property-value (parse-uuid componentId) pos value)))
        (rx/of {:axis (or rename property)
                :note (str "updated — the axis is set-wide, values are per member. "
                           "Verify with read_design.")})))))

;; --- Token tools (the safe coloring path)

(defn token-type
  "The internal token type for a public DTCG name, or nil. Accepts the
  back-compat singular aliases (`fontSize`, `fontWeight`, …) that
  `cto/dtcg-token-type->token-type` already knows."
  [name]
  (get cto/dtcg-token-type->token-type name))

(defn token-problem
  "Why `create_token` cannot author this token, or nil. Pure."
  [{:keys [type name value]}]
  (let [t (token-type type)]
    (cond
      (or (not (string? name)) (str/blank? name))
      "create_token: name is required, e.g. \"spacing.md\" or \"color.brand.primary\""

      ;; not `blank?`: "0" is a legitimate spacing value
      (or (nil? value) (and (string? value) (empty? value)))
      "create_token: value is required, e.g. \"16\", \"#6366f1\" or \"{color.blue.500}\""

      (nil? t)
      (dm/str "create_token: \"" type "\" is not a token type — use one of: "
              (str/join ", " token-type-names))

      (contains? composite-token-types t)
      (dm/str "create_token: " type " tokens need a structured value, which this"
              " tool cannot express yet — author it in the tokens panel")

      :else nil)))

;; --- Token sets and themes
;;
;; `penpot-foundations`' whole method: primitives in one set, semantics duplicated
;; across `modes/light` / `modes/dark`, and a theme per mode toggling the matching
;; set. "/" is the group separator, which is what makes `modes/*` a group.

(defn normalize-set
  "A set name as the library stores it — `\"modes / dark\"` → `\"modes/dark\"`."
  [name]
  (ctob/normalize-set-name (str name)))

(defn token-set-problem
  "Why a token cannot target this set, or nil. `nil` means Phase 08's default
  (the library's existing set), which stays untouched.

  Note the param is `set-name`, not `set`: naming it `set` shadows
  `clojure.core/set`, and `(set set-names)` then calls a string."
  [set-names set-name]
  (when (some? set-name)
    (let [want (normalize-set set-name)]
      (cond
        (empty? set-names)
        (dm/str "set \"" want "\" does not exist — this file has no token sets yet;"
                " create one with create_token_set")

        (not (contains? (into #{} set-names) want))
        (dm/str "set \"" want "\" does not exist — this file has: "
                (str/join ", " (sort set-names))
                " (create another with create_token_set)")))))

(defn new-set-problem
  "Why `create_token_set` cannot make this set, or nil."
  [set-names name]
  (let [want (normalize-set name)]
    (cond
      (or (not (string? name)) (str/blank? name))
      "create_token_set: name is required, e.g. \"modes/dark\" (\"/\" groups sets)"

      ;; create-token-set commits over an existing set — which is exactly how the
      ;; library got wiped in Phase 08. Never silently replace one.
      (contains? (into #{} set-names) want)
      (dm/str "create_token_set: a set named \"" want "\" already exists"
              " — name a token's set with create_token's `set` param instead"))))

(defn theme-problem
  "Why `create_token_theme` cannot make this theme, or nil."
  [set-names {:keys [name sets]}]
  (let [known (into #{} set-names)
        want  (mapv normalize-set sets)
        gone  (remove known want)]
    (cond
      (or (not (string? name)) (str/blank? name))
      "create_token_theme: name is required, e.g. \"Dark\""

      (empty? sets)
      (dm/str "create_token_theme: a theme needs at least one set to enable"
              " — a theme that enables nothing would activate and change nothing")

      (seq gone)
      (dm/str "create_token_theme: no set named " (str/join ", " (map #(dm/str "\"" % "\"") gone))
              " — this file has: " (str/join ", " (sort set-names))))))

(defn existing-token-set-id
  "The id of a set already in the library, or nil when it genuinely has none.

  This exists because `dwtl/create-token`'s 1-arity resolves its target through
  `lookup-token-set`, which reads `[:workspace-tokens :selected-token-set-id]` —
  the *UI selection*, not the library. Nobody has opened the Tokens panel in an
  agent session, so that is nil, and the nil branch runs `create-token-with-set`,
  which builds a fresh \"Global\" set and replaces the existing one — silently
  wiping every token in it. The first authored token after any page load would
  destroy the file's token library.

  So the set is resolved from the library and passed explicitly; the
  set-creating branch is left for the case it is actually named for."
  [state]
  (some-> (dsh/lookup-file-data state)
          :tokens-lib
          (ctob/get-sets)
          (first)
          (ctob/get-id)))

(defn- tokens-lib
  [state]
  (some-> (dsh/lookup-file-data state) :tokens-lib))

(defn- set-names
  [state]
  (some->> (tokens-lib state) (ctob/get-sets) (mapv ctob/get-name)))

(defn- set-id-by-name
  [state name]
  (let [want (normalize-set name)]
    (some->> (tokens-lib state)
             (ctob/get-sets)
             (filter #(= want (ctob/get-name %)))
             (first)
             (ctob/get-id))))

(defn- create-token
  ;; `:set` is destructured as `target-set`: binding it to `set` would shadow
  ;; clojure.core/set for the whole fn — which is exactly how token-set-problem
  ;; broke ((set xs) called a string).
  [{:keys [type name value] target-set :set :as input}]
  (let [state (deref st/state)]
    (if-let [problem (or (token-problem input)
                         (some->> (token-set-problem (set-names state) target-set)
                                  (dm/str "create_token: ")))]
      (rx/throw (ex-info problem {}))
      (let [set-id (if target-set
                     (set-id-by-name state target-set)
                     (existing-token-set-id state))
            token  (ctob/make-token {:type (token-type type) :name name :value value})]
        (st/emit! (if set-id
                    (dwtl/create-token set-id token)
                    ;; no sets at all: this is the branch that legitimately makes one
                    (dwtl/create-token token)))
        (rx/of (cond-> {:name name :type type :value value
                        :note "token created — bind it to shapes with apply_tokens"}
                 target-set (assoc :set (normalize-set target-set))))))))

(defn- create-token-set
  [{:keys [name]}]
  (let [state (deref st/state)]
    (if-let [problem (new-set-problem (set-names state) name)]
      (rx/throw (ex-info problem {}))
      (let [nm (normalize-set name)]
        (st/emit! (dwtl/create-token-set (ctob/make-token-set {:name nm})))
        (rx/of {:set nm
                :note (str "set created — aim tokens at it with create_token's `set` "
                           "param. A set is only live when an active theme enables "
                           "it: pair modes/* sets with a theme each.")})))))

(defn- create-token-theme
  [{:keys [name group sets] :as input}]
  (let [state (deref st/state)]
    (if-let [problem (theme-problem (set-names state) input)]
      (rx/throw (ex-info problem {}))
      (let [theme (ctob/make-token-theme
                   (cond-> {:name name :sets (into #{} (map normalize-set) sets)}
                     group (assoc :group group)))]
        (st/emit! (dwtl/create-token-theme theme))
        (rx/of {:theme name
                :note (str "theme created — it is not active yet; activate_theme "
                           "switches which of its sets resolve")})))))

(defn- activate-theme
  [{:keys [name]}]
  (let [state  (deref st/state)
        lib    (tokens-lib state)
        themes (some->> lib (ctob/get-themes) (remove #(= ctob/hidden-theme-name (ctob/get-name %))))
        match  (first (filter #(= name (ctob/get-name %)) themes))]
    (cond
      (nil? match)
      (rx/throw (ex-info (dm/str "activate_theme: no theme named \"" name "\""
                                 (when (seq themes)
                                   (dm/str " — this file has: "
                                           (str/join ", " (map ctob/get-name themes))))
                                 " (create one with create_token_theme)")
                         {}))

      :else
      (do
        (st/emit! (dwtl/toggle-token-theme-active (ctob/get-id match)))
        (rx/of {:theme name
                :note (str "theme toggled — shapes bound to a token NAME re-resolve "
                           "automatically, so nothing needs re-applying. Verify with "
                           "read_design.")})))))

(defn- sets-and-themes
  "The library's sets (with active flag) and themes — `read_design` flattens
  tokens into active sets only, so without this the agent cannot see that an
  inactive `modes/dark` exists at all."
  [state]
  (let [lib (tokens-lib state)]
    (when lib
      (let [active (ctob/get-active-themes-set-names lib)
            themes (->> (ctob/get-themes lib)
                        (remove #(= ctob/hidden-theme-name (ctob/get-name %)))
                        (mapv (fn [t] (cond-> {:name (ctob/get-name t)
                                               :sets (vec (:sets t))}
                                        (ctob/theme-active? lib (ctob/get-id t))
                                        (assoc :active true)))))
            sets   (->> (ctob/get-sets lib)
                        (mapv (fn [s] (cond-> {:name (ctob/get-name s)}
                                        (contains? active (ctob/get-name s))
                                        (assoc :active true)))))]
        (cond-> {}
          (seq sets)   (assoc :sets sets)
          (seq themes) (assoc :themes themes))))))

;; --- Token application
;;
;; `dwta/toggle-token` takes an arbitrary `attrs` set and the valid universe is
;; `cto/all-keys` — the color-only ceiling was entirely this file's own helper,
;; which mapped every request onto :fill or :stroke-color.

(def ^:private attr-alias
  "The plugin API's semantic names for Penpot's positional attr keys, copied
  deliberately from `app.plugins.tokens`. This is the one place where taking the
  plugin's naming is right: we take its translation layer with it, and the skills
  already speak these names (`applyToken(t, [\"columnGap\"])`). `:p1` means
  nothing without this table."
  {:r1 :border-radius-top-left
   :r2 :border-radius-top-right
   :r3 :border-radius-bottom-right
   :r4 :border-radius-bottom-left
   :p1 :padding-top
   :p2 :padding-right
   :p3 :padding-bottom
   :p4 :padding-left
   :m1 :margin-top
   :m2 :margin-right
   :m3 :margin-bottom
   :m4 :margin-left})

(def token-attr-universe
  "Every shape attribute a token can bind to (`cto/all-keys`)."
  cto/all-keys)

(defn public-attr-name
  "The camelCase name the agent uses for an internal attr keyword."
  [k]
  (str/camel (name (get attr-alias k k))))

(def ^:private public->attr
  (into {"stroke" :stroke-color} ;; legacy: the shipped spec offers "stroke"
        (map (fn [k] [(public-attr-name k) k]))
        token-attr-universe))

(defn token-attr
  "The internal attr keyword for a public name, or nil."
  [name]
  (get public->attr name))

(defn- type-attrs
  "The attrs a token of this type may bind to — the same source `toggle-token`
  itself consults (`dwta/token-properties`), so anything we accept, it accepts."
  [token-type]
  (let [{:keys [attributes all-attributes]} (get dwta/token-properties token-type)]
    (or all-attributes attributes)))

(defn application-problem
  "Why this token cannot bind to these properties, or nil. Pure."
  [token properties]
  (let [valid (type-attrs (:type token))]
    (some (fn [p]
            (if-let [k (token-attr p)]
              (when (and (seq valid) (not (contains? valid k)))
                (dm/str "apply_tokens: token \"" (:name token) "\" is a "
                        (some-> (:type token) name) " token and cannot bind to "
                        p " — it can bind to: "
                        (str/join ", " (sort (map public-attr-name valid)))))
              (dm/str "apply_tokens: \"" p "\" is not a property a token can bind"
                      " to — \"" (:name token) "\" can bind to: "
                      (str/join ", " (sort (map public-attr-name valid))))))
          properties)))

(defn attr-set
  "The internal attrs for the requested public property names, defaulting to the
  token's own default (fill for a color) when none are given."
  [token properties]
  (if (seq properties)
    (into #{} (keep token-attr) properties)
    (or (some-> (get dwta/token-properties (:type token)) :attributes) #{:fill})))

(defn- apply-tokens
  [{:keys [applications]}]
  (if (empty? applications)
    (rx/throw (ex-info "apply_tokens: no applications given" {}))
    (let [all-tokens (some-> (dsh/lookup-file-data @st/state) :tokens-lib ctob/get-all-tokens-map)]
      (interrupt!)
      (let [results
            (mapv (fn [{:keys [shapeId tokenName properties]}]
                    (let [id      (some-> shapeId parse-uuid)
                          token   (get all-tokens tokenName)
                          problem (when token (application-problem token properties))]
                      (if (and id token (nil? problem))
                        (do (st/emit! (dwta/toggle-token {:token token
                                                          :attrs (attr-set token properties)
                                                          :shape-ids [id]
                                                          :expand-with-children false}))
                            {:shapeId shapeId :token tokenName :ok true})
                        {:shapeId shapeId :token tokenName :ok false
                         :error (cond
                                  (nil? id) "invalid shapeId"
                                  (nil? token) (dm/str "no token named \"" tokenName
                                                       "\" — create it with create_token")
                                  :else problem)})))
                  applications)]
        (rx/of {:results results
                :note "tokens resolve asynchronously — verify with read_design or audit_file"})))))

;; --- Skills

(defn- get-design-skills
  [{:keys [name]}]
  (let [state @st/state]
    (rx/of (if name
             (or (ask/catalog-manifest state name)
                 ;; A miss is usually the human label ("Accessibility audit")
                 ;; rather than the key ("penpot-audit-accessibility"). Hand back
                 ;; the valid names so the retry is one round, not a guess.
                 {:error (dm/str "No skill named \"" name "\". Use one of the names below "
                                 "(the `name` field, not the label).")
                  :available (mapv :name (ask/catalog-manifest state))})
             (ask/catalog-manifest state)))))

;; --- audit_file
;;
;; On-demand scan of the current page for the active rules' violations (a fresh
;; scan, no persistent ledger — that watcher is a later story). A rule is
;; checked only when it is active (`enforced-rules`), so a file with no active
;; rules audits clean. Reuses the Phase 06 allowed-colors logic.

(def ^:private max-audit-shapes 1000)

(def ^:private default-name-re
  #"(?i)^(rectangle|ellipse|circle|board|frame|group|path|text|image|bool|curve|line|arc)(\s+\d+)?$")

(defn- default-name?
  [shape]
  (boolean (some->> (:name shape) (re-matches default-name-re))))

(defn- shape-raw-colors
  "Raw (non-token, non-allowed) fill/stroke colors on a shape."
  [shape allowed]
  (concat
   (keep (fn [f]
           (let [c (:fill-color f)]
             (when (and c (nil? (:fill-color-ref-id f)) (not (contains? allowed (normalize-hex c)))) c)))
         (:fills shape))
   (keep (fn [s]
           (let [c (:stroke-color s)]
             (when (and c (nil? (:stroke-color-ref-id s)) (not (contains? allowed (normalize-hex c)))) c)))
         (:strokes shape))))

(defn audit-violations
  [state]
  (let [objects       (dsh/lookup-page-objects state)
        check-colors? (rule-enforced? state "token-only-colors")
        check-names?  (rule-enforced? state "layer-naming")
        allowed       (when check-colors? (allowed-colors state))
        shapes        (->> (dissoc objects uuid/zero) vals (take max-audit-shapes))]
    (vec
     (concat
      (when check-colors?
        (for [s shapes
              :let [bad (seq (shape-raw-colors s allowed))]
              :when bad]
          {:rule "token-only-colors" :shapeId (dm/str (:id s)) :shapeName (:name s)
           :reason (dm/str "raw colors not bound to a token: " (str/join ", " bad))}))
      (when check-names?
        (for [s shapes
              :when (default-name? s)]
          {:rule "layer-naming" :shapeId (dm/str (:id s)) :shapeName (:name s)
           :reason "uses a default/auto-generated layer name"}))))))

(defn- audit-file
  []
  (let [violations (audit-violations @st/state)]
    (rx/of {:violations violations
            :count (count violations)
            :note (if (seq violations)
                    "fix shape by shape, then run audit_file again to confirm"
                    "no open violations for the active rules")})))

;; --- ask_user (the elicitation tool)
;;
;; The turn pauses on an ordinary tool call: the observable stores the
;; renderable questions where the panel can see them and completes only when
;; the user submits, so `run-turn` needs no notion of "waiting on a human".
;; The resolver callback is process state, not data — it lives in a module
;; atom; app-db holds only what the form renders. A cancelled turn
;; unsubscribes the tool observable, and the teardown clears the form: the
;; form must never outlive the call it belongs to.

(defonce ^:private pending-form-resolve* (atom nil))

(defn- set-pending-form
  "Publishes (or, with nil, clears) the open ask_user form for the current
  file under [:ai-panel <file-id> :pending-form]. Defined here rather than in
  data.workspace.ai-panel to keep this ns free of a require cycle (ai-panel →
  agent → agent-tools)."
  [form]
  (ptk/reify ::set-pending-form
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (if (some? form)
          (assoc-in state [:ai-panel file-id :pending-form] form)
          (update-in state [:ai-panel file-id] dissoc :pending-form))
        state))))

(defn submit-pending-form!
  "Resolves the open ask_user call with `payload` — the full tool result:
  {:answers {id value}}, plus :attachments/:images/:note when the user
  attached references (`run-tool` lifts :images into image blocks, exactly
  as for render_board). Called by the panel's form UI; a no-op when nothing
  is pending."
  [payload]
  (when-let [resolve @pending-form-resolve*]
    (reset! pending-form-resolve* nil)
    (resolve payload)))

(def ^:private max-questions 10)

(defn- questions-problem
  "Why `input` is not a usable ask_user payload, or nil when it is. Validated
  up front so the model gets an error naming the fix, not a broken form."
  [{:keys [questions]}]
  (cond
    (or (not (vector? questions)) (empty? questions))
    "ask_user needs a non-empty `questions` array"

    (> (count questions) max-questions)
    (str "ask_user allows at most " max-questions " questions per call — split the interview")

    :else
    (let [ids (map :id questions)]
      (cond
        (some #(or (not (string? %)) (str/blank? %)) ids)
        "every question needs a non-empty string `id`"

        (not= (count ids) (count (distinct ids)))
        "question `id`s must be unique"

        (some #(not (contains? #{"single" "multi" "text"} (:type %))) questions)
        "every question `type` must be \"single\", \"multi\" or \"text\""

        (some #(and (not= "text" (:type %)) (empty? (:options %))) questions)
        "single/multi questions need a non-empty `options` array"

        :else nil))))

(defn- ask-user
  [input]
  (cond
    (questions-problem input)
    (rx/throw (ex-info (questions-problem input) {}))

    ;; one form at a time: a second concurrent call is a model error, and
    ;; failing it beats silently clobbering the form the user is filling in
    (some? (deref pending-form-resolve*))
    (rx/throw (ex-info "an ask_user form is already open — wait for its answers" {}))

    :else
    (rx/create
     (fn [subs]
       (reset! pending-form-resolve*
               (fn [payload]
                 (rx/push! subs payload)
                 (rx/end! subs)))
       (st/emit! (set-pending-form (select-keys input [:title :questions])))
       (fn []
         (reset! pending-form-resolve* nil)
         (st/emit! (set-pending-form nil)))))))

;; --- set_design_doc (the project vibes document)

(defn- set-design-doc
  [{:keys [doc]}]
  (let [file-id (:current-file-id @st/state)
        doc     (when (string? doc) (str/trim doc))]
    (cond
      (nil? file-id)
      (rx/throw (ex-info "no file is open" {}))

      ;; empty means delete — the schema makes `doc` required, so an empty
      ;; string is the explicit "remove it" spelling, not an accident
      (str/blank? doc)
      (do (st/emit! (dd/clear-doc file-id))
          (rx/of {:ok true :note "design doc removed"}))

      :else
      (if-let [problem (dd/doc-problem doc)]
        (rx/throw (ex-info problem {}))
        (do (st/emit! (dd/set-doc file-id doc))
            (rx/of {:ok true
                    :chars (count doc)
                    :note (str "saved — it will be part of your instructions "
                               "from the next turn on")}))))))

;; --- explore_design (the scout tool)
;;
;; Delegates broad reading to `agent/run-side-turn` on a cheap model. The
;; runner arrives through a module atom rather than a require: agent.cljs
;; already requires THIS ns (for tool-specs/execute-tool), so requiring it
;; back would be a cycle — the same seam ask_user's resolver uses. agent.cljs
;; registers the runner at load.

(defonce ^:private side-turn-runner* (atom nil))

(defn register-side-turn-runner!
  "Called by agent.cljs at load (and by tests to stub the runner)."
  [f]
  (reset! side-turn-runner* f))

(defn registered-side-turn-runner
  "The currently registered runner (tests save/restore around a stub)."
  []
  (deref side-turn-runner*))

;; Hardcoded cheap model, same rationale as the watcher tick and compaction:
;; ambient reading never bills like design work.
(def ^:private scout-model "claude-haiku-4-5-20251001")

(def ^:private scout-tools
  ["read_design" "find_shapes" "audit_file" "get_design_skills"])

(def ^:private scout-system
  (str "You are a read-only scout inside Penpot, answering one question about "
       "the current design file for another agent. Use your tools — "
       "read_design first for orientation, find_shapes to reach inside "
       "boards, audit_file for governance findings — and be exhaustive "
       "within the question's scope.\n"
       "Answer as a compact digest the requesting agent can act on WITHOUT "
       "re-reading the file: name every relevant shape, board, component, "
       "token and page by its EXACT name (and id when you have one). Plain "
       "markdown, under 5000 characters. If the question cannot be answered "
       "with read-only tools, say precisely what is missing instead of "
       "guessing."))

(def ^:private max-digest-chars 6000)

(defn- meter-scout-usage
  "Adds the scout's spend to the panel meter. A local twin of the panel's
  accumulate-usage (requiring data.workspace.ai-panel here would cycle):
  side-context work must never be invisible spend."
  [usage]
  (ptk/reify ::meter-scout-usage
    ptk/UpdateEvent
    (update [_ state]
      (if-let [file-id (:current-file-id state)]
        (update-in state [:ai-panel file-id :usage]
                   (fn [u]
                     (merge-with + (or u {:input-tokens 0 :output-tokens 0
                                          :cache-read-tokens 0 :cache-write-tokens 0
                                          :requests 0})
                                 usage)))
        state))))

(defn- explore-design
  [{:keys [question]}]
  (let [run (deref side-turn-runner*)]
    (cond
      (or (not (string? question)) (str/blank? question))
      (rx/throw (ex-info "explore_design needs a non-empty `question`" {}))

      (nil? run)
      (rx/throw (ex-info (str "the explorer is not available in this context "
                              "— read the design directly instead")
                         {}))

      :else
      (->> (run {:model scout-model
                 :system scout-system
                 :user-text question
                 :tools scout-tools})
           (rx/map
            (fn [{:keys [text usage]}]
              (when (seq usage)
                (st/emit! (meter-scout-usage usage)))
              (when (str/blank? text)
                ;; an empty digest must not read as "nothing found"
                (throw (ex-info (str "the scout returned nothing — read the "
                                     "design directly instead")
                                {})))
              {:digest (if (> (count text) max-digest-chars)
                         (str (subs text 0 max-digest-chars)
                              "\n\n[digest truncated at " max-digest-chars
                              " chars — ask a narrower question for the rest]")
                         text)}))))))

;; --- Dispatch

(defn execute-tool
  [name input]
  (case name
    "read_design"        (rx/of (read-design))
    "find_shapes"        (find-shapes input)
    "render_board"       (render-board input)
    "get_design_skills"  (get-design-skills input)
    "explore_design"     (explore-design input)
    "ask_user"           (ask-user input)
    "set_design_doc"     (set-design-doc input)
    "audit_file"         (audit-file)
    "create_shape"       (create-shape input)
    "insert_image"       (insert-image input)
    "modify_shape"       (modify-shape input)
    "nest_shape"         (nest-shape input)
    "create_text"        (create-text input)
    "set_text"           (set-text input)
    "create_component"   (create-component input)
    "delete_shape"       (delete-shape input)
    "duplicate_shape"    (duplicate-shape input)
    "group_shapes"       (group-shapes input)
    "ungroup_shapes"     (ungroup-shapes input)
    "set_layout"         (set-layout input)
    "set_layout_child"   (set-layout-child input)
    "generate_code"      (generate-code input)
    "create_instance"    (create-instance input)
    "detach_instance"    (detach-instance input)
    "create_variant"     (create-variant input)
    "add_variant"        (add-variant input)
    "set_variant_property" (set-variant-property input)
    "create_token"       (create-token input)
    "create_token_set"   (create-token-set input)
    "create_token_theme" (create-token-theme input)
    "activate_theme"     (activate-theme input)
    "apply_tokens"       (apply-tokens input)
    (rx/throw (ex-info (dm/str "Unknown tool: " name) {}))))
