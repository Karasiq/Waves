package com.wavesplatform.data

import net.dv8tion.jda.api.JDABuilder

object DiscordSender {
  lazy val jda = JDABuilder.createDefault(sys.env("VOLK_TOKEN"))
    .build()
    .awaitReady()

  def sendMessage(channel: String, text: String): Unit = {
    jda.getTextChannelsByName(channel, false).forEach { ch =>
      ch.sendMessage(text).queue()
    }
  }
}
