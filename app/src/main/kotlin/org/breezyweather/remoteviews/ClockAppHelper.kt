/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.remoteviews

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import org.breezyweather.domain.settings.SettingsManager
import java.text.Collator

/**
 * Which app the clock on clock widgets opens. By default it is the system alarm screen;
 * a launcher such as Grace may want a specific clock app instead.
 */
object ClockAppHelper {

    data class App(val packageName: String, val label: String, val isClock: Boolean)

    /** Launchable apps, apps that handle the alarm screen first, then by name. */
    fun getLaunchableApps(context: Context): List<App> {
        val pm = context.packageManager
        val clockPackages = pm.queryIntentActivities(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
            .map { it.activityInfo.packageName }
            .toSet()
        val collator = Collator.getInstance()
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
            .asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .map { App(it.first, it.second, it.first in clockPackages) }
            .sortedWith(compareBy<App> { !it.isClock }.thenBy(collator) { it.label })
            .toList()
    }

    /** The intent the widget clock opens: the chosen app if it is still installed, else the alarm screen. */
    fun getClockIntent(context: Context): Intent {
        val chosen = SettingsManager.getInstance(context).widgetClockAppPackage
        if (!chosen.isNullOrEmpty()) {
            context.packageManager.getLaunchIntentForPackage(chosen)?.let {
                return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return Intent(AlarmClock.ACTION_SHOW_ALARMS)
    }
}
