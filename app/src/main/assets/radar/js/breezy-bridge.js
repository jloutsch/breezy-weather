/**
 * Breezy Weather — bridge so GeauxWeather's maps.js runs inside Breezy's WebView.
 *
 * - PureSkyNet.fetch: NOAA / NWS feeds don't send CORS headers, so those requests
 *   go through the native BreezyRadar.httpGet() bridge (OkHttp) instead of fetch().
 *   Other hosts use plain fetch().
 * - PureSkyWeather: the two small helpers maps.js needs (wind direction, visibility).
 *
 * This file is licensed GPL-3.0-or-later, like the GeauxWeather code it adapts.
 */
(function (global) {
  'use strict';

  // Same allowlist the GeauxWeather Android app uses for its native fetch.
  var NATIVE_HOST_RE =
    /^(?:[\w-]+\.)*(?:noaa\.gov|weather\.gov|rainviewer\.com|open-meteo\.com)$/i;

  var pending = {};
  var seq = 0;

  function hasNative() {
    return !!(global.BreezyRadar && typeof global.BreezyRadar.httpGet === 'function');
  }

  function hostAllowed(urlStr) {
    try {
      return NATIVE_HOST_RE.test(new URL(urlStr).hostname);
    } catch (e) {
      return false;
    }
  }

  function b64ToBytes(b64) {
    var bin = atob(b64 || '');
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  /** Called from Kotlin with the result of BreezyRadar.httpGet(). */
  global.__breezyNetResolve = function (id, status, bodyBase64, error) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (error || status < 200 || status >= 300) {
      p.reject(new Error(error || 'HTTP ' + status));
      return;
    }
    try {
      var bytes = b64ToBytes(bodyBase64);
      if (p.as === 'arrayBuffer') {
        p.resolve(bytes.buffer);
        return;
      }
      var text = new TextDecoder('utf-8').decode(bytes);
      p.resolve(p.as === 'json' ? JSON.parse(text || '{}') : text);
    } catch (e) {
      p.reject(e);
    }
  };

  async function plainFetch(url, as) {
    var res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    if (as === 'arrayBuffer') return res.arrayBuffer();
    if (as === 'json') return res.json();
    return res.text();
  }

  function netFetch(url, opts) {
    var as = (opts && opts.as) || 'text';
    if (!hasNative() || !hostAllowed(url)) return plainFetch(url, as);
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject, as: as };
      try {
        global.BreezyRadar.httpGet(id, url);
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  function windDir(deg) {
    if (deg == null || isNaN(Number(deg))) return '';
    var dirs = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];
    return dirs[Math.round(Number(deg) / 22.5) % 16];
  }

  function formatVis(meters, units) {
    if (meters == null || isNaN(Number(meters))) return '—';
    var m = Number(meters);
    if (units === 'metric') {
      if (m >= 1000) return (m / 1000).toFixed(1) + ' km';
      return Math.round(m) + ' m';
    }
    var mi = m / 1609.344;
    if (mi >= 10) return Math.round(mi) + ' mi';
    if (mi >= 1) return mi.toFixed(1) + ' mi';
    return Math.round(mi * 5280) + ' ft';
  }

  global.PureSkyNet = { fetch: netFetch, isNative: hasNative };
  global.PureSkyWeather = { windDir: windDir, formatVis: formatVis };
})(window);
