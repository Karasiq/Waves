name := "node-monitor"

libraryDependencies ++= Seq(
  "net.dv8tion" % "JDA" % "4.1.1_125"
) ++ Dependencies.logDeps

resolvers += Resolver.jcenterRepo

mainClass := Some("com.wavesplatform.data.VolkMain")

import sbtassembly.MergeStrategy
inTask(assembly)(
  Seq(
    test := {},
    assemblyJarName := s"node-monitor.jar",
    assemblyMergeStrategy := {
      case "module-info.class"                                  => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.concat
      case other                                                => (assemblyMergeStrategy in assembly).value(other)
    }
  )
)