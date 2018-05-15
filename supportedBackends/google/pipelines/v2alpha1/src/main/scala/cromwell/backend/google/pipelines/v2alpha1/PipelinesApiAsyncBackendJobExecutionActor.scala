package cromwell.backend.google.pipelines.v2alpha1

import cromwell.backend.BackendJobDescriptor
import cromwell.backend.google.pipelines.common.{PipelinesApiDirectoryOutput, PipelinesApiFileOutput, PipelinesApiOutput}
import cromwell.backend.standard.StandardAsyncExecutionActorParams
import cromwell.core.path.DefaultPathBuilder
import wom.callable.Callable.OutputDefinition
import wom.expression.NoIoFunctionSet
import wom.values.{GlobFunctions, WomFile, WomGlobFile, WomSingleFile, WomUnlistedDirectory}

import scala.util.Try

class PipelinesApiAsyncBackendJobExecutionActor(standardParams: StandardAsyncExecutionActorParams) extends cromwell.backend .google.pipelines.common.PipelinesApiAsyncBackendJobExecutionActor(standardParams) {
  override def generateJesOutputs(jobDescriptor: BackendJobDescriptor): Set[PipelinesApiOutput] = {
    import cats.syntax.validated._
    def evaluateFiles(output: OutputDefinition): List[WomFile] = {
      Try(
        output.expression.evaluateFiles(jobDescriptor.localInputs, NoIoFunctionSet, output.womType).map(_.toList)
      ).getOrElse(List.empty[WomFile].validNel)
        .getOrElse(List.empty)
    }

    val womFileOutputs = jobDescriptor.taskCall.callable.outputs.flatMap(evaluateFiles) map relativeLocalizationPath

    val outputs: Seq[PipelinesApiOutput] = womFileOutputs.distinct flatMap {
      _.flattenFiles flatMap {
        case unlistedDirectory: WomUnlistedDirectory => generateUnlistedDirectoryOutput(unlistedDirectory)
        case singleFile: WomSingleFile => generateJesSingleFileOutputs(singleFile)
        case globFile: WomGlobFile => generateJesGlobFileOutputs(globFile)
      }
    }

    val additionalGlobOutput = jobDescriptor.taskCall.callable.additionalGlob.toList.flatMap(generateJesGlobFileOutputs).toSet

    outputs.toSet ++ additionalGlobOutput
  }
  
  private def generateUnlistedDirectoryOutput(unlistedDirectory: WomUnlistedDirectory) = {
    val destination = callRootPath.resolve(unlistedDirectory.value.stripPrefix("/")).pathAsString
    val (relpath, disk) = relativePathAndAttachedDisk(unlistedDirectory.value, runtimeAttributes.disks)
    val directoryOutput = PipelinesApiDirectoryOutput(makeSafeJesReferenceName(unlistedDirectory.value), destination, relpath, disk)
    List(directoryOutput)
  }
  
  override def generateJesGlobFileOutputs(womFile: WomGlobFile): List[PipelinesApiOutput] = {
    val globName = GlobFunctions.globName(womFile.value)
    val globDirectory = globName + "/"
    val globListFile = globName + ".list"
    val gcsGlobDirectoryDestinationPath = callRootPath.resolve(globDirectory).pathAsString
    val gcsGlobListFileDestinationPath = callRootPath.resolve(globListFile).pathAsString

    val (_, globDirectoryDisk) = relativePathAndAttachedDisk(womFile.value, runtimeAttributes.disks)

    // We need both the glob directory and the glob list:
    List(
      // The glob directory:
      PipelinesApiDirectoryOutput(makeSafeJesReferenceName(globDirectory), gcsGlobDirectoryDestinationPath, DefaultPathBuilder.get(globDirectory + "*"), globDirectoryDisk),
      // The glob list file:
      PipelinesApiFileOutput(makeSafeJesReferenceName(globListFile), gcsGlobListFileDestinationPath, DefaultPathBuilder.get(globListFile), globDirectoryDisk)
    )
  }
}
