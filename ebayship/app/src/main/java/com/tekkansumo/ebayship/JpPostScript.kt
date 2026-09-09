package com.tekkansumo.ebayship

/**
 * 国際郵便マイページの画面に流し込む JavaScript。
 *
 * このサイトは公開 API が無く、ログインの内側にあるので入力欄の id や name を
 * こちらで決め打ちにはできない。代わりに「入力欄に付いているラベルの文言」から
 * どの項目かを推測して入れる。推測が外れる画面は Kotlin 側の学習機能で
 * セレクタを覚えさせ、次回からはそれを最優先で使う。
 *
 * 文字列テンプレート（ドル記号）は Kotlin 側に食われるので JS では使わない。
 */
object JpPostScript {

    /** 項目名 -> 画面での呼び名。学習ダイアログの選択肢にもなる。 */
    val FIELD_LABELS: List<Pair<String, String>> = listOf(
        "toName" to "お届け先 氏名",
        "toPostal" to "お届け先 郵便番号",
        "toAddress" to "お届け先 住所",
        "toCity" to "お届け先 都市",
        "toState" to "お届け先 州・県",
        "toCountry" to "お届け先 国",
        "toPhone" to "お届け先 電話番号",
        "toEmail" to "お届け先 メール",
        "fromName" to "ご依頼主 氏名",
        "fromPostal" to "ご依頼主 郵便番号",
        "fromAddress" to "ご依頼主 住所",
        "fromPhone" to "ご依頼主 電話番号",
        "content" to "内容品の品名",
        "quantity" to "内容品の個数",
        "weight" to "内容品の重量",
        "value" to "内容品の価格",
        "hsCode" to "HS コード",
        "origin" to "原産国",
        "loginId" to "ログイン ID",
        "loginPw" to "パスワード"
    )

    val JS: String = """
(function () {
  if (window.__jp) { return; }

  // 項目ごとの手がかり。前のものほど強い。
  var KW = {
    toName:    ["お届け先氏名","お届け先名","受取人名","宛名","お名前","氏名","name","recipient"],
    toPostal:  ["郵便番号","postal","zip"],
    toAddress: ["住所","address","street"],
    toCity:    ["都市","市区","市","city"],
    toState:   ["州","県名","province","state"],
    toCountry: ["国名","あて先国","国","country","destination"],
    toPhone:   ["電話","phone","tel"],
    toEmail:   ["メール","mail","email"],
    fromName:  ["依頼主氏名","ご依頼主","差出人名","差出人","依頼主","お名前","氏名","name","sender"],
    fromPostal:["郵便番号","postal","zip"],
    fromAddress:["住所","address"],
    fromPhone: ["電話","phone","tel"],
    content:   ["内容品の品名","内容品名","品名","内容品","content","description","commodity"],
    quantity:  ["個数","数量","quantity","qty","piece"],
    weight:    ["重量","重さ","weight","net weight"],
    value:     ["価格","価額","単価","金額","value","price","amount"],
    hsCode:    ["hsコード","hs code","hs","関税番号","統計品目"],
    origin:    ["原産国","生産国","origin"],
    loginId:   ["ログインid","ユーザid","ユーザーid","メールアドレス","login","userid","mail"],
    loginPw:   ["パスワード","password"]
  };

  // その項目の欄ではないと分かる語。当たると候補から外す。
  var NG = {
    toName:    ["依頼主","差出人","sender","会社","company"],
    toPostal:  ["依頼主","差出人","sender"],
    toAddress: ["依頼主","差出人","sender"],
    toPhone:   ["依頼主","差出人","sender"],
    toEmail:   ["依頼主","差出人","sender"],
    fromName:  ["お届け先","受取","recipient"],
    fromPostal:["お届け先","受取","recipient"],
    fromAddress:["お届け先","受取","recipient"],
    fromPhone: ["お届け先","受取","recipient"],
    content:   ["単位","合計"],
    quantity:  ["個数単位","合計"],
    weight:    ["単位","合計重量","総重量"],
    value:     ["単位","通貨","合計","送料","保険"],
    origin:    ["国際","原産国不明"]
  };

  // 「お届け先」の欄と「ご依頼主」の欄は同じラベルなので、
  // 見出しがどちらの区画かを見て点を足し引きする。
  var SECTION = {
    to:   ["お届け先","届け先","受取人","あて先","宛先","recipient","consignee","delivery"],
    from: ["ご依頼主","依頼主","差出人","sender","shipper"]
  };

  function norm(s) {
    if (!s) { return ""; }
    return String(s)
      .replace(/[！-～]/g, function (c) {
        return String.fromCharCode(c.charCodeAt(0) - 0xFEE0);
      })
      .replace(/\s+/g, "")
      .toLowerCase();
  }

  function visible(el) {
    if (!el) { return false; }
    if (el.disabled || el.readOnly) { return false; }
    if (el.type === "hidden") { return false; }
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) { return false; }
    var st = window.getComputedStyle(el);
    return st.visibility !== "hidden" && st.display !== "none";
  }

  function textOf(node) {
    if (!node) { return ""; }
    var t = node.textContent || "";
    return t.replace(/\s+/g, " ").trim().slice(0, 120);
  }

  /** 入力欄に付いている説明文をかき集める。 */
  function labelText(el) {
    var parts = [];
    if (el.id) {
      var l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
      if (l) { parts.push(textOf(l)); }
    }
    var up = el.closest ? el.closest("label") : null;
    if (up) { parts.push(textOf(up)); }

    // 表組みなら同じ行の見出しセル
    var td = el.closest ? el.closest("td,th,div,li,p,dd") : null;
    if (td) {
      // 入れ物そのものが説明文を持っていることがある（<p>パスワード<input></p> など）。
      // 大きな入れ物だと関係ない文まで拾うので、短いときだけ使う。
      var own = textOf(td);
      if (own.length < 60) { parts.push(own); }
      var tr = td.closest ? td.closest("tr") : null;
      if (tr) {
        var head = tr.querySelector("th");
        if (head) { parts.push(textOf(head)); }
      }
      var prev = td.previousElementSibling;
      if (prev) { parts.push(textOf(prev)); }
      var dt = td.previousElementSibling;
      if (dt && dt.tagName === "DT") { parts.push(textOf(dt)); }
    }
    // 直前のテキスト
    var p = el.previousElementSibling;
    var hops = 0;
    while (p && hops < 3) {
      parts.push(textOf(p));
      p = p.previousElementSibling;
      hops = hops + 1;
    }
    parts.push(el.placeholder || "");
    parts.push(el.getAttribute("aria-label") || "");
    parts.push(el.getAttribute("title") || "");
    parts.push(el.name || "");
    parts.push(el.id || "");
    return norm(parts.join(" "));
  }

  /** その欄がどの区画（お届け先／ご依頼主）にあるか。 */
  function sectionText(el) {
    var acc = [];
    var n = el.parentElement;
    var hops = 0;
    while (n && hops < 8) {
      var h = n.querySelector ?
        n.querySelector("legend,h1,h2,h3,h4,h5,caption,.title,.head,.heading") : null;
      if (h) { acc.push(textOf(h)); }
      n = n.parentElement;
      hops = hops + 1;
    }
    return norm(acc.join(" "));
  }

  function hasAny(hay, list) {
    for (var i = 0; i < list.length; i++) {
      if (hay.indexOf(norm(list[i])) >= 0) { return true; }
    }
    return false;
  }

  function score(el, field) {
    var kws = KW[field] || [];
    var lab = labelText(el);
    if (!lab) { return 0; }
    var best = 0;
    for (var i = 0; i < kws.length; i++) {
      var k = norm(kws[i]);
      if (k && lab.indexOf(k) >= 0) {
        // 前に置いた手がかりほど高く、長く一致するほど高い
        var s = (kws.length - i) * 10 + k.length;
        if (s > best) { best = s; }
      }
    }
    if (best === 0) { return 0; }

    var ngs = NG[field] || [];
    if (hasAny(lab, ngs)) { return 0; }

    var sec = sectionText(el);
    if (field.indexOf("to") === 0) {
      if (hasAny(sec, SECTION.from)) { best = best - 60; }
      if (hasAny(sec, SECTION.to)) { best = best + 40; }
    } else if (field.indexOf("from") === 0) {
      if (hasAny(sec, SECTION.to)) { best = best - 60; }
      if (hasAny(sec, SECTION.from)) { best = best + 40; }
    }
    if (field === "loginPw" && el.type === "password") { best = best + 50; }
    if (field === "loginPw" && el.type !== "password") { best = best - 40; }
    return best;
  }

  function candidates() {
    var all = document.querySelectorAll("input,select,textarea");
    var out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var t = (el.type || "").toLowerCase();
      if (t === "submit" || t === "button" || t === "image" ||
          t === "checkbox" || t === "radio" || t === "file") { continue; }
      if (!visible(el)) { continue; }
      out.push(el);
    }
    return out;
  }

  /** 同じ点数の欄が並ぶときは、上にあるものから使う。 */
  function findField(field, used) {
    var list = candidates();
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (used.indexOf(el) >= 0) { continue; }
      var s = score(el, field);
      if (s > bestScore) { bestScore = s; best = el; }
    }
    if (best) { return best; }
    // パスワード欄だけは、文言が何も無くても型で分かる。
    if (field === "loginPw") {
      for (var j = 0; j < list.length; j++) {
        if (list[j].type === "password" && used.indexOf(list[j]) < 0) { return list[j]; }
      }
    }
    return null;
  }

  /** React などが値を見失わないよう、ネイティブの setter を通して入れる。 */
  function setValue(el, value) {
    var proto = el.tagName === "SELECT" ? window.HTMLSelectElement.prototype :
      (el.tagName === "TEXTAREA" ? window.HTMLTextAreaElement.prototype :
        window.HTMLInputElement.prototype);
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    el.dispatchEvent(new Event("blur", { bubbles: true }));
  }

  /** プルダウンは表示文字か値のどちらかが一致する選択肢を選ぶ。 */
  function setSelect(el, value) {
    var v = norm(value);
    if (!v) { return false; }
    for (var i = 0; i < el.options.length; i++) {
      var o = el.options[i];
      if (norm(o.value) === v || norm(o.text) === v) {
        el.selectedIndex = i;
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        return true;
      }
    }
    for (var j = 0; j < el.options.length; j++) {
      var o2 = el.options[j];
      if (norm(o2.text).indexOf(v) >= 0 || v.indexOf(norm(o2.value)) >= 0) {
        el.selectedIndex = j;
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        return true;
      }
    }
    return false;
  }

  /** 学習させたセレクタから要素を引く。 */
  function bySelector(sel) {
    if (!sel) { return null; }
    try {
      var el = document.querySelector(sel);
      return (el && visible(el)) ? el : null;
    } catch (e) { return null; }
  }

  /** その要素をあとで指し直せる形で書き表す。 */
  function selectorFor(el) {
    if (el.id) { return "#" + CSS.escape(el.id); }
    if (el.name) {
      var same = document.getElementsByName(el.name);
      if (same.length === 1) {
        return el.tagName.toLowerCase() + '[name="' + el.name + '"]';
      }
      for (var i = 0; i < same.length; i++) {
        if (same[i] === el) {
          return "*[name=\"" + el.name + "\"]:nth-of-type(" + (i + 1) + ")";
        }
      }
    }
    // id も name も無いときは親からの位置で指す
    var path = [];
    var n = el;
    while (n && n.nodeType === 1 && path.length < 6) {
      var part = n.tagName.toLowerCase();
      var parent = n.parentElement;
      if (!parent) { break; }
      var idx = 1;
      var sib = parent.children;
      for (var k = 0; k < sib.length; k++) {
        if (sib[k] === n) { break; }
        if (sib[k].tagName === n.tagName) { idx = idx + 1; }
      }
      part = part + ":nth-of-type(" + idx + ")";
      path.unshift(part);
      if (parent.id) { path.unshift("#" + CSS.escape(parent.id)); break; }
      n = parent;
    }
    return path.join(" > ");
  }

  window.__jp = {

    /**
     * data: { 項目名: 入れたい値 }
     * profile: { 項目名: 覚えさせた CSS セレクタ }
     * 返り値は入った欄と入らなかった欄の一覧。
     */
    fill: function (data, profile) {
      var filled = [];
      var missing = [];
      var used = [];
      var keys = Object.keys(data);
      for (var i = 0; i < keys.length; i++) {
        var f = keys[i];
        var v = data[f];
        if (v === null || v === undefined || v === "") { continue; }
        var el = bySelector(profile ? profile[f] : null);
        var learned = !!el;
        if (!el) { el = findField(f, used); }
        if (!el) { missing.push(f); continue; }
        used.push(el);
        // 値に "||" が入っていたら言い換えの候補。プルダウンで順に試す。
        var alts = String(v).split("||");
        var ok = true;
        if (el.tagName === "SELECT") {
          ok = false;
          for (var a = 0; a < alts.length; a++) {
            if (setSelect(el, alts[a])) { ok = true; break; }
          }
        } else {
          setValue(el, alts[0]);
        }
        if (!ok) { missing.push(f); continue; }
        filled.push({
          field: f,
          value: alts[0],
          label: labelText(el).slice(0, 40),
          selector: selectorFor(el),
          learned: learned
        });
      }
      return JSON.stringify({ filled: filled, missing: missing, url: location.href });
    },

    /** 画面にどんな入力欄があるか一覧で吐く。うまく入らないときの調べもの用。 */
    describe: function () {
      var list = candidates();
      var out = [];
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        out.push({
          tag: el.tagName.toLowerCase(),
          type: el.type || "",
          name: el.name || "",
          id: el.id || "",
          label: labelText(el).slice(0, 60),
          section: sectionText(el).slice(0, 40),
          value: (el.value || "").slice(0, 30),
          selector: selectorFor(el)
        });
      }
      return JSON.stringify({ url: location.href, fields: out });
    },

    /** 覚えさせたい欄を指で選んでもらうモード。 */
    pick: function (on) {
      if (window.__jpPick) {
        document.removeEventListener("click", window.__jpPick, true);
        window.__jpPick = null;
      }
      if (!on) { return "off"; }
      window.__jpPick = function (ev) {
        var el = ev.target;
        if (!el || !el.tagName) { return; }
        var tag = el.tagName.toLowerCase();
        if (tag !== "input" && tag !== "select" && tag !== "textarea") { return; }
        ev.preventDefault();
        ev.stopPropagation();
        el.style.outline = "3px solid #e0a458";
        if (window.Jp && window.Jp.picked) {
          window.Jp.picked(selectorFor(el), labelText(el).slice(0, 40), tag);
        }
      };
      document.addEventListener("click", window.__jpPick, true);
      return "on";
    },

    /** 「次へ」「登録」などのボタンを文言で押す。 */
    press: function (words) {
      var list = document.querySelectorAll(
        "button,input[type=submit],input[type=button],a[href],[role=button]"
      );
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        var t = norm((el.value || "") + " " + textOf(el));
        for (var j = 0; j < words.length; j++) {
          if (t.indexOf(norm(words[j])) >= 0) {
            var r = el.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) { continue; }
            el.click();
            return "pressed:" + t.slice(0, 30);
          }
        }
      }
      return "";
    }
  };
  return "ready";
})();
"""
}
