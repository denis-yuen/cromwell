package cwl

import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.validated._
import cats.syntax.option._
import common.validation.ErrorOr.ErrorOr
import common.validation.Validation._
import io.circe.{Json, JsonNumber, JsonObject}
import io.circe._
import io.circe.generic.auto._
import io.circe.refined._
import io.circe.literal._
import wom.executable.Executable.DelayedCoercionFunction
import wom.types._
import wom.values._

// With recursive types we could let circe parse it for us, but until we figure that out just parse it as Json and
// manually check for File / Directory structures
private [cwl] object CwlJsonToDelayedCoercionFunction extends Json.Folder[DelayedCoercionFunction] {
  import cwl.decoder._
  implicit val fileD = implicitly[Decoder[File]]
  implicit val directoryD = implicitly[Decoder[Directory]]
  
  private def simpleCoercion(value: Any)(womType: WomType) = womType.coerceRawValue(value).toErrorOr

  override def onNull = womType => { WomOptionalValue.none(womType).validNel }
  override def onBoolean(value: Boolean) = simpleCoercion(value)
  override def onNumber(value: JsonNumber) = {
    case WomFloatType => WomFloat(value.toDouble).validNel
    case WomIntegerType => value.toInt.map(WomInteger.apply).toValidNel(s"$value is not a valid Int")
    case WomLongType => value.toLong.map(WomLong.apply).toValidNel(s"$value is not a valid Long")
    case other => other.coerceRawValue(value.toString).toErrorOr
  }
  override def onString(value: String) = simpleCoercion(value)

  override def onArray(value: Vector[Json]) = {
    case womArrayType: WomArrayType =>
      value.toList
        .traverse(_.foldWith(this).apply(womArrayType.memberType))
        .map {
          WomArray(womArrayType, _)
        }

    case WomOptionalType(otherType) =>
      onArray(value).apply(otherType)
    case WomCoproductType(types) =>
      val attempts: List[ErrorOr[WomValue]] = types.toList.map(onArray(value)(_))
      attempts.find(_.isValid).getOrElse(attempts.sequence.map(_.head))
    case WomAnyType =>
      // Make an array of WomAny
      value.toList
        .traverse(_.foldWith(this).apply(WomAnyType))
        .map {
          WomArray(WomArrayType(WomAnyType), _)
        }
    case other => s"Cannot convert an array input value into a non array type: $other".invalidNel
  }

  override def onObject(value: JsonObject) = {
    // CWL files are represented as Json objects, so this could be a file 
    case WomSingleFileType | WomMaybePopulatedFileType if value.toMap.get("class").flatMap(_.asString).contains("File") =>
      Json.fromJsonObject(value).as[File] match {
        case Left(errors) => errors.message.invalidNel

        /*
          From the CWL spec:
          If no location or path is specified, a file object must specify contents with the UTF-8 text content of the file.
          This is a "file literal". File literals do not correspond to external resources, but are created on disk with
          contents with when needed for a executing a tool. Where appropriate, expressions can return file literals to
          define new files on a runtime. The maximum size of contents is 64 kilobytes.


          NOTE WELL:
          This implementation is incompatible with cloud backends, as the file is local and thus inaccessible from
          those environments.  Please see this issue:
          https://github.com/broadinstitute/cromwell/issues/3581
        */
        case Right(file@File(_, None, None, _, _,_,_,_,Some(contents))) => {
          val tempDir = better.files.File.newTemporaryDirectory()
          val cwlFile: better.files.File = tempDir./(s"${contents.hashCode}").write(contents)
          file.asWomValue.map(_.copy(valueOption = Option(cwlFile.path.toString)))
        }
        case Right(file) => file.asWomValue
      }
    case WomMaybeListedDirectoryType | WomUnlistedDirectoryType if value.toMap.get("class").flatMap(_.asString).contains("Directory") =>
      Json.fromJsonObject(value).as[Directory] match {
        case Left(errors) => errors.message.invalidNel
        case Right(directory) => directory.asWomValue
      }
    case composite: WomCompositeType =>
      val foldedMap = value.toList.traverse({
        case (k, v) =>
          composite.typeMap.get(k).map({ valueType =>
            v.foldWith(this).apply(valueType).map(k -> _)
          }).getOrElse(s"Input field $k is not defined in the composite input type $composite".invalidNel)
      }).map(_.toMap[String, WomValue])

      foldedMap.map(WomObject.withType(_, composite))
    case WomObjectType =>
      val foldedMap = value.toList.traverse({
        case (k, v) => v.foldWith(this).apply(WomAnyType).map(k -> _)
      }).map(_.toMap[String, WomValue])

      foldedMap.map(WomObject.apply)
    case WomOptionalType(otherType) => onObject(value).apply(otherType)
    case WomCoproductType(types) =>
      val attempts: List[ErrorOr[WomValue]] = types.toList.map(onObject(value)(_))
      //these are all Invalid, just taking head to satisfy required type of WomValue instead of List[WomValue]
      attempts.find(_.isValid).getOrElse(attempts.sequence.map(_.head))
    case other => s"Cannot convert an object value $value into a non array type: $other".invalidNel
  }
}
