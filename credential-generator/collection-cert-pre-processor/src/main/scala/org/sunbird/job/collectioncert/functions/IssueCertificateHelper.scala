package org.sunbird.job.collectioncert.functions

import java.text.SimpleDateFormat
import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.{Row, TypeTokens}
import com.twitter.util.Config.intoOption
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.cache.DataCache
import org.sunbird.job.collectioncert.domain.{AssessmentUserAttempt, BEJobRequestEvent, EnrolledUser, Event, EventObject}
import org.sunbird.job.collectioncert.task.CollectionCertPreProcessorConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, ScalaJsonUtil}

import scala.collection.JavaConverters._

trait IssueCertificateHelper {
    private[this] val logger = LoggerFactory.getLogger(classOf[CollectionCertPreProcessorFn])


    def issueCertificate(event:Event, template: Map[String, String])(cassandraUtil: CassandraUtil, cache:DataCache, contentCache: DataCache, metrics: Metrics, config: CollectionCertPreProcessorConfig, httpUtil: HttpUtil): String = {
        logger.info(s"inside issueCertificate ")
        //validCriteria
        val criteria = validateTemplate(template, event.batchId)(config)
        logger.info(s"criteria :: ${criteria} ")
        //validateEnrolmentCriteria
        val certName = template.getOrElse(config.name, "")
        logger.info(s"certName :: ${certName} ")
        val enrolledUser: EnrolledUser = validateEnrolmentCriteria(event, criteria.getOrElse(config.enrollment, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]], certName)(metrics, cassandraUtil, config)
        logger.info(s"enrolledUser :: ${enrolledUser} ")
        //validateAssessmentCriteria
        val assessedUser = validateAssessmentCriteria(event, criteria.getOrElse(config.assessment, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]], enrolledUser.userId)(metrics, cassandraUtil, contentCache, config)
        logger.info(s"assessedUser :: ${assessedUser} ")
        //validateUserCriteria
        val userDetails = validateUser(assessedUser, criteria.getOrElse(config.user, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]])(metrics, config, httpUtil)
        logger.info(s"userDetails :: ${userDetails} ")

        //generateCertificateEvent
        if(userDetails.nonEmpty) {
            generateCertificateEvent(event, template, userDetails, enrolledUser, certName)(metrics, config, cache, httpUtil)
        } else {
            logger.info(s"""User :: ${event.userId} did not match the criteria for batch :: ${event.batchId} and course :: ${event.courseId}""")
            null
        }
    }
    
    def validateTemplate(template: Map[String, String], batchId: String)(config: CollectionCertPreProcessorConfig):Map[String, AnyRef] = {
        val criteria = ScalaJsonUtil.deserialize[Map[String, AnyRef]](template.getOrElse(config.criteria, "{}"))
        if(!template.getOrElse("url", "").isEmpty && !criteria.isEmpty && !criteria.keySet.intersect(Set(config.enrollment, config.assessment, config.users)).isEmpty) {
            criteria
        } else {
            throw new Exception(s"Invalid template for batch : ${batchId}")
        }
    }

    def validateEnrolmentCriteria(event: Event, enrollmentCriteria: Map[String, AnyRef], certName: String)(metrics:Metrics, cassandraUtil: CassandraUtil, config:CollectionCertPreProcessorConfig): EnrolledUser = {
        if(!enrollmentCriteria.isEmpty) {
            val query = QueryBuilder.select("active", "issued_certificates", "completedon", "status").from(config.keyspace, config.userEnrolmentsTable)
              .where(QueryBuilder.eq(config.dbUserId, event.userId)).and(QueryBuilder.eq(config.dbCourseId, event.courseId))
              .and(QueryBuilder.eq(config.dbBatchId, event.batchId))
            val row = cassandraUtil.findOne(query.toString)
            metrics.incCounter(config.dbReadCount)
            if(null != row){
                val active:Boolean = row.getBool(config.active)   
                val issuedCertificates = row.getList(config.issuedCertificates, TypeTokens.mapOf(classOf[String], classOf[String])).asScala.toList
                val isCertIssued = !issuedCertificates.isEmpty && !issuedCertificates.filter(cert => certName.equalsIgnoreCase(cert.getOrDefault(config.name,"").asInstanceOf[String])).isEmpty
                val status = row.getInt(config.status)
                val criteriaStatus = enrollmentCriteria.getOrElse(config.status, 2)
                val oldId = if(isCertIssued && event.reIssue) issuedCertificates.filter(cert => certName.equalsIgnoreCase(cert.getOrDefault(config.name,"").asInstanceOf[String]))
                  .map(cert => cert.getOrDefault(config.identifier, "")).head else ""
                val userId = if(active && (criteriaStatus == status) && (!isCertIssued || event.reIssue)) event.userId else ""
                val issuedOn = row.getTimestamp(config.completedOn)
                EnrolledUser(userId, oldId, issuedOn)
            } else EnrolledUser("", "")
        } else EnrolledUser(event.userId, "") 
    }

    def validateAssessmentCriteria(event: Event, assessmentCriteria: Map[String, AnyRef], enrolledUser: String)(metrics:Metrics, cassandraUtil: CassandraUtil, contentCache: DataCache, config:CollectionCertPreProcessorConfig):String = {
        var assessedUser = enrolledUser

        if(!assessmentCriteria.isEmpty && !enrolledUser.isEmpty) {
            val score:Double = getMaxScore(event)(metrics, cassandraUtil, config, contentCache)
            if(!isValidAssessCriteria(assessmentCriteria, score))
                assessedUser = ""
        }
        assessedUser
    }

    def validateUser(userId: String, userCriteria: Map[String, AnyRef])(metrics:Metrics, config:CollectionCertPreProcessorConfig, httpUtil: HttpUtil) = {
        if(!userId.isEmpty) {
            val url = config.learnerBasePath + config.userReadApi + "/" + userId
            val result = getAPICall(url, "response")(config, httpUtil, metrics)
            if(userCriteria.isEmpty || userCriteria.size == userCriteria.filter(uc => uc._2 == result.getOrElse(uc._1, null)).size) {
                result
            } else Map[String, AnyRef]()
        } else Map[String, AnyRef]()
    }

    def getMaxScore(event: Event)(metrics:Metrics, cassandraUtil: CassandraUtil, config:CollectionCertPreProcessorConfig, contentCache: DataCache):Double = {
        val query = QueryBuilder.select().column("content_id").column("total_max_score").column("total_score").as("score").from(config.keyspace, config.assessmentTable)
          .where(QueryBuilder.eq("course_id", event.courseId)).and(QueryBuilder.eq("batch_id", event.batchId))
          .and(QueryBuilder.eq("user_id", event.userId))
        
        val rows: java.util.List[Row] = cassandraUtil.find(query.toString)
        metrics.incCounter(config.dbReadCount)
        if(null != rows && !rows.isEmpty) {
          val userAssessments = rows.asScala.toList.map(row => AssessmentUserAttempt(row.getString("content_id"), row.getDouble("score"), row.getDouble("total_max_score")))
            .groupBy(f => f.contentId)
          val filteredUserAssessments = userAssessments.filterKeys(key => {
            val metadata = contentCache.getWithRetry(key)
            if (metadata.nonEmpty) {
              val contentType = metadata.getOrElse("contenttype", "")
              config.assessmentContentTypes.contains(contentType)
            } else throw new Exception("Metadata cache not available for: " + key)
          })
          // TODO: Here we have an assumption that, we will consider max percentage from all the available attempts of different assessment contents.
          if (filteredUserAssessments.nonEmpty) filteredUserAssessments.values.flatten.map(a => (a.score*100/a.totalScore)).max else 0d
        } else 0d
    }

    def isValidAssessCriteria(assessmentCriteria: Map[String, AnyRef], score: Double): Boolean = {
        if(assessmentCriteria.get("score").isInstanceOf[Number]) {
            score == assessmentCriteria.get("score").asInstanceOf[Int].toDouble
        } else {
            val scoreCriteria = assessmentCriteria.getOrElse("score", Map[String, AnyRef]()).asInstanceOf[Map[String, Int]]
            if(scoreCriteria.isEmpty) false
            else {
                val operation = scoreCriteria.head._1
                val criteriaScore = scoreCriteria.head._2.toDouble
                operation match {
                    case "EQ" => score == criteriaScore
                    case "eq" => score == criteriaScore
                    case "=" => score == criteriaScore
                    case ">" => score > criteriaScore
                    case "<" => score < criteriaScore
                    case ">=" => score >= criteriaScore
                    case "<=" => score <= criteriaScore
                    case "ne" => score != criteriaScore
                    case "!=" => score != criteriaScore
                    case _ => false
                }
            }
            
        }
    }
    
    def getAPICall(url: String, responseParam: String)(config:CollectionCertPreProcessorConfig, httpUtil: HttpUtil, metrics: Metrics): Map[String,AnyRef] = {
        val response = httpUtil.get(url, config.defaultHeaders)
        if(200 == response.status) {
            ScalaJsonUtil.deserialize[Map[String, AnyRef]](response.body)
              .getOrElse("result", Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
              .getOrElse(responseParam, Map[String, AnyRef]()).asInstanceOf[Map[String, AnyRef]]
        } else if(400 == response.status && response.body.contains("USER_ACCOUNT_BLOCKED")) {
            metrics.incCounter(config.skippedEventCount)
            logger.error(s"Error while fetching user details for ${url}: " + response.status + " :: " + response.body)
            Map[String, AnyRef]()
        } else {
            throw new Exception(s"Error from get API : ${url}, with response: ${response}")
        }
    }

    def getCourseName(courseId: String)(metrics:Metrics, config:CollectionCertPreProcessorConfig, cache:DataCache, httpUtil: HttpUtil): String = {
        val courseMetadata = cache.getWithRetry(courseId)
        if(null == courseMetadata || courseMetadata.isEmpty) {
            val url = config.contentBasePath + config.contentReadApi + "/" + courseId + "?fields=name"
            val response = getAPICall(url, "content")(config, httpUtil, metrics)
            StringContext.processEscapes(response.getOrElse(config.name, "").asInstanceOf[String]).filter(_ >= ' ')
        } else {
            StringContext.processEscapes(courseMetadata.getOrElse(config.name, "").asInstanceOf[String]).filter(_ >= ' ')
        }
    }

    def extract(m: collection.Map[String, AnyRef], key: String): Option[String] = {
        if (m.isDefinedAt(key)) Some(m(key).toString)
        else {
            val res = m.values
              .collect { case ma: Map[String, AnyRef] => extract(ma, key) }
              .dropWhile(_.isEmpty)
            if (res.isEmpty) None else res.head
        }
    }

    def generateCertificateEvent(event: Event, template: Map[String, String], userDetails: Map[String, AnyRef], enrolledUser: EnrolledUser, certName: String)(metrics:Metrics, config:CollectionCertPreProcessorConfig, cache:DataCache, httpUtil: HttpUtil) = {
        logger.info(s"generateCertificateEvent called ")
        val firstName = Option(userDetails.getOrElse("firstName", "").asInstanceOf[String]).getOrElse("")
        val lastName = Option(userDetails.getOrElse("lastName", "").asInstanceOf[String]).getOrElse("")
        def nullStringCheck(name:String):String = {if(StringUtils.equalsIgnoreCase("null", name)) ""  else name}
        val recipientName = nullStringCheck(firstName).concat(" ").concat(nullStringCheck(lastName)).trim
        val courseName = getCourseName(event.courseId)(metrics, config, cache, httpUtil)
        val dateFormatter = new SimpleDateFormat("yyyy-MM-dd")
        val profileDetails : Map[String, AnyRef] = userDetails.getOrElse("profileDetails", "").asInstanceOf[Map[String, AnyRef]]
        val profileReq : Map[String, AnyRef] = profileDetails.getOrElse("profileReq", "").asInstanceOf[Map[String, AnyRef]]
        val personalDetails : Map[String, AnyRef] = profileReq.getOrElse("personalDetails", "").asInstanceOf[Map[String, AnyRef]]
        val professionalDetails : List[Map[String, AnyRef]] = profileReq.getOrElse("professionalDetails", Nil).asInstanceOf[List[Map[String, AnyRef]]]
        logger.info(s"personalDetails :: ${personalDetails} ")
        logger.info(s"professionalDetails :: ${professionalDetails} ")
        var orgName: String = ""
        if (!professionalDetails.isEmpty) {
            val organizationDetails: Map[String, AnyRef] = professionalDetails.head
            logger.info(s"organizationDetails :: ${organizationDetails} ")
            if (!organizationDetails.isEmpty) {
                orgName = Option(organizationDetails.getOrElse("name", "[NA]").asInstanceOf[String]).getOrElse("[NA]")
                logger.info(s"orgName :: ${orgName} ")
            }
        }
        var address = Array[String]()
        var country: String = "[NA]"
        var state: String = "[NA]"
        var district: String = "[NA]"
        val postalAddress = Option(personalDetails.getOrElse("postalAddress", "[NA]").asInstanceOf[String]).getOrElse("[NA]")
        if(!postalAddress.isBlank) {
            logger.info(s"postalAddress :: ${postalAddress} ")
            address = postalAddress.split(", ")
            if (!address.isEmpty) {
                country = address(0).getOrElse("[NA]")
                state = address(1).getOrElse("[NA]")
                district = address(2).getOrElse("[NA]")
                logger.info(s"country :: ${country} ")
                logger.info(s"state :: ${state} ")
                logger.info(s"district :: ${district} ")
            }
        }
        val regNurseRegMidwifeNumber = Option(personalDetails.getOrElse("regNurseRegMidwifeNumber", "[NA]").asInstanceOf[String]).getOrElse("[NA]")
        val eData = Map[String, AnyRef] (
            "issuedDate" -> dateFormatter.format(enrolledUser.issuedOn),
            "data" -> List(Map[String, AnyRef]("recipientName" -> recipientName, "recipientId" -> event.userId)),
            "criteria" -> Map[String, String]("narrative" -> certName),
            "svgTemplate" -> template.getOrElse("url", ""),
            "oldId" -> enrolledUser.oldId,
            "templateId" -> template.getOrElse(config.identifier, ""),
            "userId" -> event.userId,
            "orgId" -> userDetails.getOrElse("rootOrgId", ""),
            "issuer" -> ScalaJsonUtil.deserialize[Map[String, AnyRef]](template.getOrElse(config.issuer, "{}")),
            "signatoryList" -> ScalaJsonUtil.deserialize[List[Map[String, AnyRef]]](template.getOrElse(config.signatoryList, "[]")),
            "courseName" -> courseName,
            "basePath" -> config.certBasePath,
            "related" ->  Map[String, AnyRef]("batchId" -> event.batchId, "courseId" -> event.courseId, "type" -> certName),
            "name" -> certName,
            "rmNumber" -> regNurseRegMidwifeNumber,
            "orgName" -> orgName,
            "country" -> country,
            "state" -> state,
            "district" -> district,
            "tag" -> event.batchId
        )

        ScalaJsonUtil.serialize(BEJobRequestEvent(edata = eData, `object` = EventObject(id= event.userId)))
    }
}
