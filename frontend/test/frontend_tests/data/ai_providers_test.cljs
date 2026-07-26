;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.ai-providers-test
  "Locks in the curated model catalog against provider reality.

  These assertions are not really testing our code — they pin down external
  facts verified against provider documentation on 2026-07-15, with the
  Moonshot rows re-verified on 2026-07-21 for the K3 launch. The catalog is
  hand-maintained, and the failure mode of getting a row wrong is a provider
  error the user sees as a generic red bubble.

  The point is that no single instinct gets vision right across providers:
  Anthropic and OpenAI read images on every current model; Moonshot's current
  models are natively multimodal (the base/`-vision-preview` split died with
  the retired moonshot-v1 line); while Zhipu STILL ships vision as separate
  ids — `glm-5.2` is text-only and there is no `glm-5.2v`."
  (:require
   [app.main.data.ai-providers :as dai]
   [cljs.test :as t :include-macros true]))

(t/deftest anthropic-models-all-see
  (t/testing "every current Claude model takes image input"
    (t/is (true? (dai/vision? "anthropic" "claude-fable-5")))
    (t/is (true? (dai/vision? "anthropic" "claude-opus-4-8")))
    (t/is (true? (dai/vision? "anthropic" "claude-sonnet-5")))
    (t/is (true? (dai/vision? "anthropic" "claude-haiku-4-5-20251001")))))

(t/deftest openai-models-all-see
  (t/testing "OpenAI has no separate vision SKU — every current model takes images"
    (t/is (true? (dai/vision? "openai" "gpt-5.6-sol")))
    (t/is (true? (dai/vision? "openai" "gpt-5.6-terra")))
    (t/is (true? (dai/vision? "openai" "gpt-5.6-luna")))
    (t/is (true? (dai/vision? "openai" "gpt-5.4-mini")))))

(t/deftest zhipu-splits-vision-into-separate-models
  (t/testing "the base GLM models are text-only however capable they sound"
    (t/is (false? (dai/vision? "zhipu" "glm-5.2")))
    (t/is (false? (dai/vision? "zhipu" "glm-4.7"))))
  (t/testing "glm-5v-turbo is the vision one — one character away from
              glm-5-turbo, which is text-only and deliberately not offered"
    (t/is (true? (dai/vision? "zhipu" "glm-5v-turbo")))
    (t/is (false? (dai/vision? "zhipu" "glm-5-turbo")) "not in the catalog")))

(t/deftest moonshot-current-models-are-natively-multimodal
  (t/testing "the base/-vision-preview split died with the moonshot-v1 line"
    (t/is (true? (dai/vision? "moonshot" "kimi-k3")))
    (t/is (true? (dai/vision? "moonshot" "kimi-k2.6")))
    (t/is (true? (dai/vision? "moonshot" "kimi-k2.7-code")))
    (t/is (true? (dai/vision? "moonshot" "kimi-k2.7-code-highspeed")))))

(t/deftest kimi-k3-is-the-only-million-token-moonshot-model
  (t/testing "the rest of the Kimi line is 256K — a wrong context here shows up
              as history trimmed far too early or a provider-side overflow"
    (let [by-id (into {} (map (juxt :id :context))
                      (get dai/ai-provider-models "moonshot"))]
      (t/is (= 1000000 (get by-id "kimi-k3")))
      (t/is (= 262144 (get by-id "kimi-k2.6")))
      (t/is (= 262144 (get by-id "kimi-k2.7-code"))))))

(t/deftest retired-models-are-not-offered
  (t/testing "an id that fails at the provider must not be in the picker"
    (doseq [[provider id reason]
            [["moonshot" "kimi-k2-0905-preview" "discontinued 2026-05-25"]
             ["moonshot" "kimi-k2.5" "closed to new accounts, sunset 2026-08-31"]
             ["moonshot" "moonshot-v1-128k" "sunset 2026-08-31"]
             ["openai" "gpt-5" "deprecated 2026-06-11, shutdown 2026-12-11"]
             ["openai" "gpt-5-mini" "deprecated 2026-06-11, shutdown 2026-12-11"]]]
      (t/is (not (contains? (set (map :id (get dai/ai-provider-models provider))) id))
            (str id ": " reason)))))

(t/deftest unknown-models-are-assumed-blind
  (t/testing "a user can enable a model that is not in the catalog; guessing
              wrong toward `true` costs a provider error mid-conversation,
              guessing wrong toward `false` only greys out a button"
    (t/is (false? (dai/vision? "anthropic" "claude-some-future-model")))
    (t/is (false? (dai/vision? "openai" "gpt-6")))
    (t/is (false? (dai/vision? "no-such-provider" "gpt-5.6-sol")))
    (t/is (false? (dai/vision? nil nil)))))

(t/deftest every-provider-offers-at-least-one-vision-model
  (t/testing "image attachments are dead on a provider with no vision model —
              if this fails, that provider's users silently lose the feature"
    (doseq [[provider models] dai/ai-provider-models]
      (t/is (some :vision models)
            (str provider " offers no vision-capable model")))))

(t/deftest catalog-entries-are-well-formed
  (t/testing "every row carries the keys both the settings UI and the agent read"
    (doseq [[provider models] dai/ai-provider-models
            model models]
      (t/is (string? (:id model)) (str provider " model id"))
      (t/is (string? (:label model)) (str provider " " (:id model) " label"))
      (t/is (pos-int? (:context model)) (str provider " " (:id model) " context"))
      (t/is (boolean? (:vision model))
            (str provider " " (:id model) " must state vision explicitly")))))

(t/deftest catalog-ids-are-unique-within-a-provider
  (doseq [[provider models] dai/ai-provider-models]
    (let [ids (map :id models)]
      (t/is (= (count ids) (count (distinct ids)))
            (str provider " has a duplicate model id")))))
