import scrollIntoView from "smooth-scroll-into-view-if-needed"
import isPlainObject from "lodash/isPlainObject"

const count = xs => xs ? xs.n : 0
const cons = (x, xs) => ({head: x, tail: xs, n: count(xs) + 1})
const vec = xs => {
  const arr = Array(count(xs));
  let i = count(xs) - 1;
  while (xs !== null) {
    arr[i] = xs.head;
    --i;
    xs = xs.tail;
  }
  return arr;
}

window.__CUIC__ = {
  scrollIntoView: async node => {
    await scrollIntoView(node, {
      scrollMode: "if-needed",
      block: "center",
      inline: "nearest",
    })
  },
  isInViewport: node => {
    const rect = node.getBoundingClientRect();
    return (
      rect.top >= 0 &&
      rect.left >= 0 &&
      rect.bottom <= (window.innerHeight || document.documentElement.clientHeight) &&
      rect.right <= (window.innerWidth || document.documentElement.clientWidth)
    );
  },
  wrapResult: x => {
    const refs = [];
    const result = {};
    result.str = (function stringify(v, path) {
      const t = typeof v;
      if (t === "number" || t === "string" || t === "boolean" || v === null) {
        return JSON.stringify(v);
      } else if (t === "undefined") {
        return "null";
      } else if (Array.isArray(v)) {
        return "[" + v.map((x, i) => stringify(x, cons(i, path))).join(",") + "]";
      } else if (isPlainObject(v)) {
        return "{" + Object.keys(v).map(k => `${JSON.stringify(k)}:${stringify(v[k], cons(k, path))}`).join(",") + "}";
      } else {
        result["ref_" + refs.length] = v;
        refs.push(vec(path));
        return "null";
      }
    })(x, null);
    if (refs.length > 0) {
      result.refs = JSON.stringify(refs);
    }
    return result;
  }
}
