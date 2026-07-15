;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.ai-providers-test
  "Locks in the vision capability of the curated model catalog.

  These assertions are not testing our code so much as pinning down external
  facts that were verified against provider documentation on 2026-07-15 — the
  catalog is hand-maintained, and the failure mode of getting one wrong is a
  provider error the user sees as a generic red bubble. The split is the whole
  point: GLM and Moonshot ship vision as *separate* model IDs (`glm-4.5v`,
  `moonshot-v1-*-vision-preview`), so the base models named here are text-only
  no matter how capable the family sounds."
  (:require
   [app.main.data.ai-providers :as dai]
   [cljs.test :as t :include-macros true]))

(t/deftest anthropic-models-all-see
  (t/is (true? (dai/vision? "anthropic" "claude-opus-4-8")))
  (t/is (true? (dai/vision? "anthropic" "claude-sonnet-5")))
  (t/is (true? (dai/vision? "anthropic" "claude-haiku-4-5-20251001"))))

(t/deftest openai-models-all-see
  (t/testing "OpenAI has no separate vision SKU — every current model takes images"
    (t/is (true? (dai/vision? "openai" "gpt-5")))
    (t/is (true? (dai/vision? "openai" "gpt-5-mini")))
    (t/is (true? (dai/vision? "openai" "gpt-4.1")))))

(t/deftest zhipu-models-are-text-only
  (t/testing "the GLM vision line is glm-4.xV — these base models cannot see"
    (t/is (false? (dai/vision? "zhipu" "glm-4.6")))
    (t/is (false? (dai/vision? "zhipu" "glm-4.5")))
    (t/is (false? (dai/vision? "zhipu" "glm-4.5-air")))))

(t/deftest moonshot-models-are-text-only
  (t/testing "only moonshot-v1-*-vision-preview accepts images"
    (t/is (false? (dai/vision? "moonshot" "kimi-k2-0905-preview")))
    (t/is (false? (dai/vision? "moonshot" "moonshot-v1-128k")))
    (t/is (false? (dai/vision? "moonshot" "moonshot-v1-32k")))))

(t/deftest unknown-models-are-assumed-blind
  (t/testing "a user can enable a model that is not in the catalog; guessing
              wrong toward `true` costs a provider error mid-conversation,
              guessing wrong toward `false` only greys out a button"
    (t/is (false? (dai/vision? "anthropic" "claude-some-future-model")))
    (t/is (false? (dai/vision? "openai" "gpt-6")))
    (t/is (false? (dai/vision? "no-such-provider" "gpt-5")))
    (t/is (false? (dai/vision? nil nil)))))

(t/deftest catalog-entries-are-well-formed
  (t/testing "every row carries the keys both the settings UI and the agent read"
    (doseq [[provider models] dai/ai-provider-models
            model models]
      (t/is (string? (:id model)) (str provider " model id"))
      (t/is (string? (:label model)) (str provider " " (:id model) " label"))
      (t/is (boolean? (:vision model))
            (str provider " " (:id model) " must state vision explicitly")))))
