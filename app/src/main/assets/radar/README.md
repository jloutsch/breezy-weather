# Radar / weather maps

This folder is the Radar screen (`org.breezyweather.ui.radar.RadarActivity`), shown in a WebView.

| Files | Origin | License |
|---|---|---|
| `js/maps.js`, `js/kmz.js`, `css/maps.css` | [GeauxWeather](https://github.com/jloutsch/GeauxWeather) (`www/js`, `public/css/maps-web.css`) | GPL-3.0-or-later (`LICENSE-GPL-3.0.txt`) |
| `js/breezy-bridge.js`, `js/breezy-main.js`, `index.html` | Written for Breezy, adapting the above | GPL-3.0-or-later |
| `lib/leaflet/` | [Leaflet](https://leafletjs.com) 1.9.4 | BSD-2-Clause |
| `data/eclipses.json` | GeauxWeather | GPL-3.0-or-later |

Because these files are GPL-3.0-or-later, an APK that includes them is distributed under the
GPL-3.0-or-later as a whole. LGPL-3.0 code may be conveyed under the GPL, so this is allowed.

Data at runtime: RainViewer (radar tiles), OpenStreetMap (base map), Open-Meteo (wind, fog, rain
outlook), NOAA NHC (hurricanes), NWS (tornado / severe alerts), USGS (river stages).

To update from GeauxWeather, copy `www/js/maps.js` and `www/js/kmz.js` over the files here.
They talk to the page only through `window.PureSkyNet`, `window.PureSkyWeather` (provided by
`breezy-bridge.js`) and the element ids in `index.html`.
