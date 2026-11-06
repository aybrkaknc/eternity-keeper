(function (scope) {
  // Layout problems that a screenshot at one size would hide: text clipped by
  // its own box, anything pushed off screen, controls too small to hit, and
  // (the one light mode keeps catching us out on) text that has faded into
  // its background.
  var issues = [];
  var root = document.querySelector(scope);
  if (!root) return {error: 'no ' + scope};

  function visible(el) {
    var s = window.getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function label(el) {
    var id = el.id ? '#' + el.id : '';
    var cls = (el.className && typeof el.className === 'string')
      ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    return el.tagName.toLowerCase() + id + cls;
  }

  function parse(colour) {
    var m = /rgba?\(([^)]+)\)/.exec(colour || '');
    if (!m) return null;
    var p = m[1].split(',').map(function (v) { return parseFloat(v); });
    return {r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1};
  }

  // Composite a colour over what is actually behind it, or a translucent
  // rgba(0,0,0,.04) panel reads as solid black and every check lies.
  function ground(el) {
    var stack = [];
    for (var node = el; node && node !== document.documentElement; node = node.parentElement) {
      var style = window.getComputedStyle(node);
      var c = parse(style.backgroundColor);
      if (c && c.a > 0) {
        stack.push(c);
        if (c.a > 0.99) break;
      }

      // Buttons and panels here are painted with gradients, whose
      // backgroundColor is transparent. Ignoring them composites a pale label
      // straight onto the page and reports a contrast failure that is not
      // there.
      var stop = /rgba?\([^)]+\)/.exec(style.backgroundImage || '');
      if (stop) {
        var g = parse(stop[0]);
        if (g && g.a > 0) {
          stack.push(g);
          if (g.a > 0.99) break;
        }
      }
    }
    // The page itself paints a gradient, not a background-colour, so walking
    // up the tree ends at a transparent body. Taking the gradient's first
    // stop as the ground is what stops every dark-theme label from being
    // measured against imaginary white.
    stack.push(pageGround());

    var out = stack[stack.length - 1];
    for (var i = stack.length - 2; i >= 0; i--) {
      var top = stack[i];
      out = {
        r: top.r * top.a + out.r * (1 - top.a),
        g: top.g * top.a + out.g * (1 - top.a),
        b: top.b * top.a + out.b * (1 - top.a),
        a: 1
      };
    }
    return out;
  }

  var _ground = null;

  function pageGround() {
    if (_ground) return _ground;
    var body = window.getComputedStyle(document.body);
    var solid = parse(body.backgroundColor);
    if (solid && solid.a > 0.99) {
      _ground = solid;
      return _ground;
    }

    var stop = /rgba?\([^)]+\)/.exec(body.backgroundImage || '');
    _ground = (stop && parse(stop[0])) || {r: 255, g: 255, b: 255, a: 1};
    _ground.a = 1;
    return _ground;
  }

  function luminance(c) {
    var v = [c.r, c.g, c.b].map(function (x) {
      x = x / 255;
      return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
  }

  function contrast(fg, bg) {
    var a = luminance(fg) + 0.05, b = luminance(bg) + 0.05;
    return a > b ? a / b : b / a;
  }

  var worst = null;
  var all = root.querySelectorAll('*');
  for (var i = 0; i < all.length; i++) {
    var el = all[i];
    if (!visible(el)) continue;
    var r = el.getBoundingClientRect(), s = window.getComputedStyle(el);

    if (r.right > window.innerWidth + 2 || r.left < -2) {
      issues.push({kind: 'off screen', el: label(el),
                   left: Math.round(r.left), right: Math.round(r.right)});
    }

    if ((el.tagName === 'BUTTON' || el.tagName === 'INPUT')
        && (r.height < 12 || r.width < 12)) {
      issues.push({kind: 'too small', el: label(el),
                   size: Math.round(r.width) + 'x' + Math.round(r.height)});
    }

    var text = (el.textContent || '').trim();
    if (el.children.length === 0 && text.length > 0) {
      var ox = el.scrollWidth - el.clientWidth > 2;
      var oy = el.scrollHeight - el.clientHeight > 2;
      var caught = s.overflow !== 'visible' || s.overflowX !== 'visible'
        || s.textOverflow === 'ellipsis';
      if ((ox || oy) && !caught) {
        issues.push({kind: 'text overflows', el: label(el), text: text.slice(0, 40)});
      }

      var fg = parse(s.color);
      if (fg && fg.a > 0.3) {
        var opacity = parseFloat(s.opacity);
        var effective = {r: fg.r, g: fg.g, b: fg.b, a: fg.a};
        var bg = ground(el);
        if (opacity < 1) {
          effective = {
            r: fg.r * opacity + bg.r * (1 - opacity),
            g: fg.g * opacity + bg.g * (1 - opacity),
            b: fg.b * opacity + bg.b * (1 - opacity), a: 1};
        }
        var ratio = contrast(effective, bg);
        // Something deliberately dimmed to say "you cannot use this" is
        // supposed to be low contrast; that is the signal. Weapon sets III
        // and IV are hatched out until a talent unlocks them.
        var muted = false;
        for (var p = el; p && p !== root; p = p.parentElement) {
          var c = (p.className && typeof p.className === 'string') ? p.className : '';
          if (/-locked|-blocked|-unsellable|disabled/.test(c)) { muted = true; break; }
        }

        if (ratio < 3 && !muted) {
          issues.push({kind: 'low contrast', el: label(el),
                       ratio: Math.round(ratio * 10) / 10, text: text.slice(0, 34)});
        }
        if (muted) continue;

        if (!worst || ratio < worst.ratio) {
          worst = {ratio: Math.round(ratio * 10) / 10, el: label(el),
                   text: text.slice(0, 34)};
        }
      }
    }
  }

  return {issues: issues, worstContrast: worst};
})
