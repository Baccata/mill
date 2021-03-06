package mill.scalalib.publish

import java.util.Base64



import scala.concurrent.duration._


class SonatypeHttpApi(uri: String, credentials: String) {
  val http = requests.Session(connectTimeout = 5000, readTimeout = 1000, maxRedirects = 0)

  private val base64Creds = base64(credentials)

  private val commonHeaders = Seq(
    "Authorization" -> s"Basic $base64Creds",
    "Accept" -> "application/json",
    "Content-Type" -> "application/json"
  )

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles.html
  def getStagingProfileUri(groupId: String): String = {
    val response = withRetry(
      http.get(
        s"$uri/staging/profiles",
        headers = commonHeaders
      )
    )

    if (!response.is2xx) {
      throw new Exception(s"$uri/staging/profiles returned ${response.statusCode}")
    }

    val resourceUri =
      ujson
        .read(response.data.text)("data")
        .arr
        .find(profile =>
          groupId.split('.').startsWith(profile("name").str.split('.')))
        .map(_("resourceURI").str.toString)

    resourceUri.getOrElse(
      throw new RuntimeException(
        s"Could not find staging profile for groupId: ${groupId}")
    )
  }

  def getStagingRepoState(stagingRepoId: String): String = {
    val response = http.get(
      s"${uri}/staging/repository/${stagingRepoId}",
      readTimeout = 60000,
      headers = commonHeaders
    )
    ujson.read(response.data.text)("type").str.toString
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_start.html
  def createStagingRepo(profileUri: String, groupId: String): String = {
    val response = http.post(
      s"${profileUri}/start",
      headers = commonHeaders,
      data = s"""{"data": {"description": "fresh staging profile for ${groupId}"}}"""
    )

    if (!response.is2xx) {
      throw new Exception(s"$uri/staging/profiles returned ${response.statusCode}")
    }

    ujson.read(response.data.text)("data")("stagedRepositoryId").str.toString
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_finish.html
  def closeStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/finish",
        headers = commonHeaders,
        data = s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "closing staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_promote.html
  def promoteStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/promote",
        headers = commonHeaders,
        data = s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "promote staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_drop.html
  def dropStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/drop",
        headers = commonHeaders,
        data = s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "drop staging repository"}}"""
      )
    )
    response.statusCode == 201
  }

  private val uploadTimeout = 5.minutes.toMillis.toInt

  def upload(uri: String, data: Array[Byte]): requests.Response = {
    http.put(
      uri,
      readTimeout = uploadTimeout,
      headers = Seq(
        "Content-Type" -> "application/binary",
        "Authorization" -> s"Basic ${base64Creds}"
      ),
      data = data
    )
  }

  private def withRetry(request: => requests.Response,
                        retries: Int = 10): requests.Response = {
    val resp = request
    if (resp.is5xx && retries > 0) {
      Thread.sleep(500)
      withRetry(request, retries - 1)
    } else {
      resp
    }
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))

}
