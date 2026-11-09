/**
 * Breezy Weather — starts GeauxWeather's maps.js for the location Breezy passes in.
 *
 * Query string (set by RadarActivity): lat, lon, name, units (metric|imperial), layer.
 * This file is licensed GPL-3.0-or-later, like the GeauxWeather code it adapts.
 */
(function (global) {
  'use strict';

  var params = new URLSearchParams(global.location.search);
  var units = params.get('units') === 'metric' ? 'metric' : 'imperial';
  var lat = parseFloat(params.get('lat'));
  var lon = parseFloat(params.get('lon'));
  var name = params.get('name') || '';
  var layer = params.get('layer') || 'radar';

  // maps.js reads the unit preference from localStorage.
  try {
    localStorage.setItem('units', units);
  } catch (e) {
    /* storage unavailable: maps.js falls back to imperial */
  }

  function coordLabel(la, lo) {
    return (
      Math.abs(la).toFixed(2) + '°' + (la >= 0 ? 'N' : 'S') + ', ' +
      Math.abs(lo).toFixed(2) + '°' + (lo >= 0 ? 'E' : 'W')
    );
  }

  /** The "current" block of an Open-Meteo forecast: what maps.js reads for Wind and Fog. */
  async function fetchCurrent(la, lo) {
    var url =
      'https://api.open-meteo.com/v1/forecast?latitude=' + la +
      '&longitude=' + lo +
      '&current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,' +
      'wind_gusts_10m,visibility,precipitation,rain,cape' +
      '&temperature_unit=' + (units === 'metric' ? 'celsius' : 'fahrenheit') +
      '&wind_speed_unit=' + (units === 'metric' ? 'kmh' : 'mph') +
      '&precipitation_unit=' + (units === 'metric' ? 'mm' : 'inch') +
      '&timezone=auto';
    var res = await fetch(url, { cache: 'no-cache' });
    if (!res.ok) throw new Error('Open-Meteo ' + res.status);
    return res.json();
  }

  function loadForecast(la, lo) {
    fetchCurrent(la, lo)
      .then(function (data) {
        if (global.PureSkyMaps) global.PureSkyMaps.setForecast(data);
      })
      .catch(function (e) {
        console.warn('Breezy radar: forecast failed', e);
      });
  }

  function start() {
    var maps = global.PureSkyMaps;
    if (!maps) {
      console.error('Breezy radar: maps.js did not load');
      return;
    }

    document.querySelectorAll('.layer-btn[data-layer]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        maps.setLayer(btn.getAttribute('data-layer'));
      });
    });

    // maps.js has already moved the marker (and reloaded the pin's rain outlook) by now.
    maps.onPinDrop(function (loc) {
      loc.label = coordLabel(loc.lat, loc.lon);
      maps.updatePinHint('Pin · ' + loc.label);
      loadForecast(loc.lat, loc.lon);
    });

    var home = null;
    if (!isNaN(lat) && !isNaN(lon)) {
      home = { lat: lat, lon: lon, label: name || coordLabel(lat, lon) };
    }
    maps.init(home, null);
    if (layer !== 'radar') maps.setLayer(layer);
    if (home) {
      maps.updatePinHint(home.label + ' · tap map to drop a pin');
      loadForecast(home.lat, home.lon);
    }

    // Re-measure after the WebView settles its final size.
    global.addEventListener('resize', function () {
      maps.onShow();
    });
  }

  /** Called by RadarActivity when the screen comes back to the foreground. */
  global.__breezyRadarResume = function () {
    if (global.PureSkyMaps && global.PureSkyMaps.reloadRadar) global.PureSkyMaps.reloadRadar();
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})(window);
