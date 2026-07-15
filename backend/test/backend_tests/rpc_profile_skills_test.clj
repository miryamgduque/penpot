;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-profile-skills-test
  "User-created personal skills (US #9): create + list, scoped per profile."
  (:require
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest profile-skill-create-and-list
  (let [prof1       (th/create-profile* 1 {:is-active true})
        prof2       (th/create-profile* 2 {:is-active true})
        pid1        (:id prof1)
        pid2        (:id prof2)
        create      (fn [pid & {:as params}]
                      (th/command! (merge {::th/type :create-skill
                                           ::rpc/profile-id pid}
                                          params)))
        list-skills (fn [pid]
                      (:result (th/command! {::th/type :get-skills
                                             ::rpc/profile-id pid})))]

    (t/testing "no skills initially"
      (t/is (empty? (list-skills pid1))))

    (t/testing "create returns the row, default enabled true"
      (let [out (create pid1
                        :name "tone-of-voice-checker"
                        :label "Tone of voice checker"
                        :category "Audits"
                        :mode "suggest"
                        :trigger "Check the tone of voice on this screen."
                        :description "Reviews copy against a described tone."
                        :body "# Tone of voice checker\n\nFlag copy that doesn't match.")]
        (t/is (nil? (:error out)))
        (t/is (= "tone-of-voice-checker" (:name (:result out))))
        (t/is (= "Audits" (:category (:result out))))
        (t/is (= "suggest" (:mode (:result out))))
        (t/is (= true (:enabled (:result out))))
        (t/is (some? (:id (:result out))))))

    (t/testing "get lists the created skill"
      (let [skills (list-skills pid1)]
        (t/is (= 1 (count skills)))
        (t/is (= "Tone of voice checker" (:label (first skills))))
        (t/is (= "Check the tone of voice on this screen." (:trigger (first skills))))))

    (t/testing "a duplicate name is auto-suffixed, not rejected"
      (let [out (create pid1
                        :name "tone-of-voice-checker"
                        :label "Tone of voice checker (again)"
                        :category "Audits"
                        :mode "suggest"
                        :body "# again")]
        (t/is (nil? (:error out)))
        (t/is (= "tone-of-voice-checker-2" (:name (:result out))))
        (t/is (= 2 (count (list-skills pid1))))))

    (t/testing "a second profile does not see the first's skills"
      (t/is (empty? (list-skills pid2)))
      (create pid2 :name "my-skill" :label "My skill" :category "Build" :mode "review" :body "# b")
      (t/is (= 1 (count (list-skills pid2))))
      (t/is (= 2 (count (list-skills pid1)))))))
