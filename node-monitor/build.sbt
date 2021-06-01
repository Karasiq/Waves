name := "node-monitor"

libraryDependencies ++= Seq(
  "net.dv8tion" % "JDA" % "4.1.1_125"
) ++ Dependencies.logDeps

resolvers += Resolver.jcenterRepo
