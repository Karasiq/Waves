import WavesDockerKeys._

name := "blockchain-updates"

libraryDependencies ++= Seq(
  "net.dv8tion" % "JDA" % "4.1.1_125"
) ++ Dependencies.grpc

resolvers += Resolver.jcenterRepo

extensionClasses += "com.wavesplatform.events.BlockchainUpdates"

inConfig(Compile)(
  Seq(
    PB.protoSources in Compile := Seq(PB.externalIncludePath.value),
    includeFilter in PB.generate := { (f: File) =>
      (** / "waves" / "events" / ** / "*.proto").matches(f.toPath)
    },
    PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value
  )
)

enablePlugins(RunApplicationSettings, WavesExtensionDockerPlugin, ExtensionPackaging)

docker := docker.dependsOn(LocalProject("node-it") / docker).value
inTask(docker)(
  Seq(
    imageNames := Seq(ImageName("karasiq/waves-node-monitor:latest")),
    exposedPorts := Set(6880, 6881),
    additionalFiles ++= Seq(
      (LocalProject("blockchain-updates") / Universal / stage).value
    )
  )
)
