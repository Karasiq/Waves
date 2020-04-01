import WavesDockerKeys._

enablePlugins(WavesDockerPlugin, IntegrationTestsPlugin)

description := "NODE integration tests"
libraryDependencies ++= Dependencies.it

def stageFiles(ref: ProjectReference): TaskKey[File] =
  ref / Universal / stage

(Test / test) := (Test / test).dependsOn(Docker / docker).value

inTask(docker)(
  Seq(
    imageNames := Seq(
      ImageName("com.wavesplatform/node-it"),
      // 1.1.7-1-g0da0ad7-DIRTY -> 1.1.7.1
      ImageName(s"wavesplatform/wavesnode:${"""(\d+\.\d+\.\d+(?:-\d+)).*$""".r.replaceAllIn(version.value, "$1").replace("-", ".")}")
    ),
    exposedPorts := Set(6863, 6869, 6870), // NetworkApi, RestApi, gRPC
    additionalFiles ++= Seq(
      stageFiles(LocalProject("node")).value,
      stageFiles(LocalProject("grpc-server")).value,
      (Test / resourceDirectory).value / "template.conf",
      (Test / sourceDirectory).value / "container" / "start-waves.sh"
    )
  )
)
