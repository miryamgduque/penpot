;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.netguard-test
  "The predicate table for the exporter's private-network guard. Every entry
  here is an address class an attacker has actually used for SSRF somewhere;
  a regression on any row is a security bug, not a style issue."
  (:require
   [app.util.netguard :as ng]
   [cljs.test :as t :include-macros true]))

(t/deftest loopback-and-unspecified-are-private
  (doseq [ip ["127.0.0.1" "127.8.9.10" "0.0.0.0" "::1" "::"]]
    (t/is (true? (ng/ip-private? ip)) ip)))

(t/deftest rfc1918-ranges-are-private
  (doseq [ip ["10.0.0.1" "10.255.255.255"
              "172.16.0.1" "172.31.255.254"
              "192.168.1.1"]]
    (t/is (true? (ng/ip-private? ip)) ip)))

(t/deftest rfc1918-lookalikes-are-public
  (doseq [ip ["172.15.0.1" "172.32.0.1" "11.0.0.1" "192.169.0.1"]]
    (t/is (false? (ng/ip-private? ip)) ip)))

(t/deftest cloud-metadata-and-link-local-are-private
  (doseq [ip ["169.254.169.254" "169.254.0.1" "fe80::1"]]
    (t/is (true? (ng/ip-private? ip)) ip)))

(t/deftest cgnat-and-benchmarking-are-private
  (doseq [ip ["100.64.0.1" "100.127.255.255" "198.18.0.1" "198.19.255.255"]]
    (t/is (true? (ng/ip-private? ip)) ip)))

(t/deftest cgnat-boundaries-are-public
  (doseq [ip ["100.63.255.255" "100.128.0.1" "198.17.255.255" "198.20.0.1"]]
    (t/is (false? (ng/ip-private? ip)) ip)))

(t/deftest multicast-and-reserved-are-private
  (doseq [ip ["224.0.0.1" "240.1.1.1" "255.255.255.255" "ff02::1"]]
    (t/is (true? (ng/ip-private? ip)) ip)))

(t/deftest ipv6-private-ranges-are-private
  (doseq [ip ["fc00::1" "fd12:3456::1" "fd00:ec2::254" "fec0::1"]]
    (t/is (true? (ng/ip-private? ip)) ip)))

(t/deftest v4-mapped-forms-are-private
  (t/testing "::ffff:* is blocked wholesale — mapped addresses only appear in tricks"
    (doseq [ip ["::ffff:127.0.0.1" "::ffff:10.0.0.1" "::ffff:8.8.8.8"]]
      (t/is (true? (ng/ip-private? ip)) ip))))

(t/deftest public-addresses-are-public
  (doseq [ip ["8.8.8.8" "93.184.216.34" "1.1.1.1"
              "2606:4700::6810:84e5" "2001:4860:4860::8888"]]
    (t/is (false? (ng/ip-private? ip)) ip)))

(t/deftest non-literals-fail-closed
  (t/testing "the predicate never vouches for something it could not parse"
    (doseq [s ["localhost" "0x7f000001" "2130706433" "" "example.com"]]
      (t/is (true? (ng/ip-private? s)) s))))
