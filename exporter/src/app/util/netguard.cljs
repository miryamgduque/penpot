;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.util.netguard
  "Private-network guard for exporter features that navigate to
  attacker-controlled URLs (the JVM backend has `app.util.ssrf`; this is its
  smaller Node twin). A hostname is resolved through `dns.lookup` — the same
  getaddrinfo path the browser uses, which also parses exotic IPv4 encodings
  like decimal or hex literals — and rejected when ANY resolved address is
  private, loopback, link-local, CGNAT, multicast or otherwise reserved.
  The predicate fails CLOSED: anything unparseable counts as private."
  (:require
   ["node:dns/promises" :as dns]
   ["node:net" :as net]
   [app.common.exceptions :as ex]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(defn- ipv4-private?
  [ip]
  (let [[a b] (map #(js/parseInt % 10) (str/split ip "."))]
    (or (= a 0)                        ; "this network"
        (= a 10)                       ; RFC1918
        (= a 127)                      ; loopback
        (and (= a 169) (= b 254))      ; link-local (incl. 169.254.169.254)
        (and (= a 172) (<= 16 b 31))   ; RFC1918
        (and (= a 192) (= b 168))      ; RFC1918
        (and (= a 100) (<= 64 b 127))  ; CGNAT 100.64/10
        (and (= a 192) (= b 0))        ; 192.0.0/24 + TEST-NET-1 192.0.2/24
        (and (= a 198) (<= 18 b 19))   ; benchmarking 198.18/15
        (and (= a 198) (= b 51))       ; TEST-NET-2 (over-broad /16: fine for a deny)
        (and (= a 203) (= b 0))        ; TEST-NET-3 (over-broad /16: fine for a deny)
        (>= a 224))))                  ; multicast, 240/4 reserved, broadcast

(defn- first-hextet
  [ip]
  (let [ip (if (str/starts-with? ip "::") (str "0" ip) ip)
        h  (js/parseInt (first (str/split ip ":")) 16)]
    (if (js/isNaN h) -1 h)))

(defn- ipv6-private?
  [ip]
  (let [h (first-hextet (str/lower ip))]
    (or (neg? h)                       ; unparseable — fail closed
        ;; 0 covers ::, ::1, and every v4-mapped ::ffff:* form; over-blocking
        ;; mapped-public addresses is deliberate (they only appear in tricks)
        (zero? h)
        (<= 0xfe80 h 0xfeff)           ; link-local + site-local
        (<= 0xfc00 h 0xfdff)           ; ULA fc00::/7 (incl. fd00:ec2::254)
        (>= h 0xff00))))               ; multicast

(defn ip-private?
  "True when `ip` is a private/reserved address literal — or not an IP literal
  at all (resolve hostnames first; the predicate fails closed)."
  [ip]
  (case (net/isIP ip)
    4 (ipv4-private? ip)
    6 (ipv6-private? ip)
    true))

(defn- blocked!
  [host]
  (p/rejected (ex/error :type :validation
                        :code :blocked-host
                        :hint (str "the host '" host "' is private or could "
                                   "not be resolved"))))

(defn assert-public-host!
  "Promise of nil when `host` resolves exclusively to public addresses;
  rejects with `:blocked-host` otherwise (including resolution failures)."
  [host]
  (cond
    (or (not (string? host)) (str/blank? host))
    (blocked! host)

    ;; a literal needs no resolution — but strip the brackets an URL keeps
    ;; around IPv6 hosts first
    (pos? (net/isIP (str/trim (str/replace host #"[\[\]]" ""))))
    (let [ip (str/trim (str/replace host #"[\[\]]" ""))]
      (if (ip-private? ip)
        (blocked! host)
        (p/resolved nil)))

    :else
    (-> (dns/lookup host #js {:all true :verbatim true})
        (p/then (fn [addrs]
                  (let [ips (map #(unchecked-get % "address") (seq addrs))]
                    (if (or (empty? ips) (some ip-private? ips))
                      (blocked! host)
                      nil))))
        (p/catch (fn [cause]
                   (if (= :validation (:type (ex-data cause)))
                     (p/rejected cause)
                     (blocked! host)))))))
