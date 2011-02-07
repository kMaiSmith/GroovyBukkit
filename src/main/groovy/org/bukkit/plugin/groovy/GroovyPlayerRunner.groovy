package org.bukkit.plugin.groovy

import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector


class GroovyPlayerRunner extends GroovyRunner {

	Player player


	GroovyPlayerRunner(GroovyPlugin plugin, Player player, def data) {
		super(plugin, data)
		this.player = player
		this.world = player.world
	}


	def getInitScriptsLoc() {
		scriptLoc + player.name + '/'
	}


	Map _runContext() {
		def vars = super._runContext()
		Location location = player.location
		vars.me = player
		vars.inv = player.inventory
		vars.here = location
		vars.at = lookingat(player)

		Vector vector = v(location.x, location.y - 1.0, location.z)
		def x = vector.blockX
		def y = vector.blockY
		def z = vector.blockZ
		vars.x = x
		vars.y = y
		vars.z = z

		def yaw = location.yaw
		def fF = f(yaw)
		def fR = f(yaw + 90)
		def fL = f(yaw - 90)
		def fB = f(yaw + 180)
		vars.fac = fF
		vars.fRgt = fR
		vars.fLft = fL
		vars.fBck = fB

		def block = world[vector]
		vars.blk = block
		vars.bFwd = block + fF
		vars.bRgt = block + fR
		vars.bLft = block + fL
		vars.bBck = block + fB
		vars
	}


	World getWorld() {
		player.world
	}


//
// Bukkit helpers
//


	def make(String name, int qty = 1) {
		make(name, player.location, qty)
	}


	void inv(int pos, def item, int qty = 1) {
		player.inventory.setItem(pos, i(item, qty))
	}


	void give(def item, int qty = 1) {
		give(player, item, qty)
	}


	void to(def loc) {
		def lastloc = player.location
		def dest = l(loc) ?: lastloc
		if (dest.pitch == 0.0 && dest.yaw == 0.0) { dest.pitch = lastloc.pitch; dest.yaw = lastloc.yaw }
		player.teleportTo(dest)
		data.lastloc = lastloc
	}


	void back() {
		to(data.lastloc)
	}


	void msg(Object... args) {
		msg(player, args)
	}

}
