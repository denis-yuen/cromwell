package cromwell.backend.google.pipelines.v2alpha1.api

import cromwell.backend.BackendJobDescriptor
import cromwell.backend.google.pipelines.common.PipelinesApiFileOutput
import cromwell.backend.standard.StandardAsyncExecutionActorParams
import wom.callable.Callable.OutputDefinition
import wom.expression.NoIoFunctionSet
import wom.values.{WomFile, WomGlobFile, WomSingleFile, WomUnlistedDirectory}

import scala.util.Try

class PipelinesApiAsyncBackendJobExecutionActor(standardParams: StandardAsyncExecutionActorParams) extends cromwell.backend .google.pipelines.common.PipelinesApiAsyncBackendJobExecutionActor(standardParams) {
  override def generateJesOutputs(jobDescriptor: BackendJobDescriptor): Set[PipelinesApiFileOutput] = {
    import cats.syntax.validated._
    def evaluateFiles(output: OutputDefinition): List[WomFile] = {
      Try(
        output.expression.evaluateFiles(jobDescriptor.localInputs, NoIoFunctionSet, output.womType).map(_.toList)
      ).getOrElse(List.empty[WomFile].validNel)
        .getOrElse(List.empty)
    }

    val womFileOutputs = jobDescriptor.taskCall.callable.outputs.flatMap(evaluateFiles) map relativeLocalizationPath

    val outputs: Seq[PipelinesApiFileOutput] = womFileOutputs.distinct flatMap {
      _.flattenFiles flatMap {
        case unlistedDirectory: WomUnlistedDirectory => generateUnlistedDirectoryOutputs(unlistedDirectory)
        case singleFile: WomSingleFile => generateJesSingleFileOutputs(singleFile)
        case globFile: WomGlobFile => generateJesGlobFileOutputs(globFile)
      }
    }

    val additionalGlobOutput = jobDescriptor.taskCall.callable.additionalGlob.toList.flatMap(generateJesGlobFileOutputs).toSet

    outputs.toSet ++ additionalGlobOutput
  }
}
