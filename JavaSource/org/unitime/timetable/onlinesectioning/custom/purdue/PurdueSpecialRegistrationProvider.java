/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * The Apereo Foundation licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
*/
package org.unitime.timetable.onlinesectioning.custom.purdue;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.restlet.Client;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.MediaType;
import org.restlet.data.Protocol;
import org.restlet.resource.ClientResource;
import org.unitime.localization.impl.Localization;
import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.defaults.ApplicationProperty;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.Student;
import org.unitime.timetable.model.dao.CourseOfferingDAO;
import org.unitime.timetable.model.dao.StudentDAO;
import org.unitime.timetable.gwt.resources.StudentSectioningMessages;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.ClassAssignment;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.CourseAssignment;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface.ErrorMessage;
import org.unitime.timetable.gwt.shared.SectioningException;
import org.unitime.timetable.gwt.shared.SpecialRegistrationInterface.SpecialRegistrationEligibilityRequest;
import org.unitime.timetable.gwt.shared.SpecialRegistrationInterface.SpecialRegistrationEligibilityResponse;
import org.unitime.timetable.gwt.shared.SpecialRegistrationInterface.RetrieveSpecialRegistrationRequest;
import org.unitime.timetable.gwt.shared.SpecialRegistrationInterface.RetrieveSpecialRegistrationResponse;
import org.unitime.timetable.gwt.shared.SpecialRegistrationInterface.SubmitSpecialRegistrationRequest;
import org.unitime.timetable.gwt.shared.SpecialRegistrationInterface.SubmitSpecialRegistrationResponse;
import org.unitime.timetable.interfaces.ExternalClassLookupInterface;
import org.unitime.timetable.onlinesectioning.AcademicSessionInfo;
import org.unitime.timetable.onlinesectioning.OnlineSectioningHelper;
import org.unitime.timetable.onlinesectioning.OnlineSectioningServer;
import org.unitime.timetable.onlinesectioning.basic.GetAssignment;
import org.unitime.timetable.onlinesectioning.custom.ExternalTermProvider;
import org.unitime.timetable.onlinesectioning.custom.SpecialRegistrationProvider;
import org.unitime.timetable.onlinesectioning.custom.StudentEnrollmentProvider.EnrollmentRequest;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.Change;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.ChangeError;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.ChangeOperation;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.SpecialRegistrationRequest;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.SpecialRegistrationResponse;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.SpecialRegistrationResponseStatus;
import org.unitime.timetable.onlinesectioning.custom.purdue.SpecialRegistrationInterface.SpecialRegistrationStatus;
import org.unitime.timetable.onlinesectioning.model.XConfig;
import org.unitime.timetable.onlinesectioning.model.XCourse;
import org.unitime.timetable.onlinesectioning.model.XCourseId;
import org.unitime.timetable.onlinesectioning.model.XCourseRequest;
import org.unitime.timetable.onlinesectioning.model.XEnrollment;
import org.unitime.timetable.onlinesectioning.model.XEnrollments;
import org.unitime.timetable.onlinesectioning.model.XOffering;
import org.unitime.timetable.onlinesectioning.model.XRequest;
import org.unitime.timetable.onlinesectioning.model.XReservation;
import org.unitime.timetable.onlinesectioning.model.XSection;
import org.unitime.timetable.onlinesectioning.model.XStudent;
import org.unitime.timetable.onlinesectioning.model.XSubpart;
import org.unitime.timetable.util.DefaultExternalClassLookup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * @author Tomas Muller
 */
public class PurdueSpecialRegistrationProvider implements SpecialRegistrationProvider {
	private static Logger sLog = Logger.getLogger(PurdueSpecialRegistrationProvider.class);
	private static StudentSectioningMessages MSG = Localization.create(StudentSectioningMessages.class);

	private Client iClient;
	private ExternalTermProvider iExternalTermProvider;
	private ExternalClassLookupInterface iExternalClassLookup;
	
	public PurdueSpecialRegistrationProvider() {
		List<Protocol> protocols = new ArrayList<Protocol>();
		protocols.add(Protocol.HTTP);
		protocols.add(Protocol.HTTPS);
		iClient = new Client(protocols);
		try {
			String clazz = ApplicationProperty.CustomizationExternalTerm.value();
			if (clazz == null || clazz.isEmpty())
				iExternalTermProvider = new BannerTermProvider();
			else
				iExternalTermProvider = (ExternalTermProvider)Class.forName(clazz).getConstructor().newInstance();
		} catch (Exception e) {
			sLog.error("Failed to create external term provider, using the default one instead.", e);
			iExternalTermProvider = new BannerTermProvider();
		}
		try {
			String clazz = ApplicationProperty.CustomizationExternalClassLookup.value();
			if (clazz == null || clazz.isEmpty())
				iExternalClassLookup = new DefaultExternalClassLookup();
			else
				iExternalClassLookup = (ExternalClassLookupInterface)Class.forName(clazz).getConstructor().newInstance();
		} catch (Exception e) {
			sLog.error("Failed to create external class lookup, using the default one instead.", e);
			iExternalClassLookup = new DefaultExternalClassLookup();
		}
	}
	
	protected String getSpecialRegistrationApiSiteCheckEligibility() {
		return ApplicationProperties.getProperty("purdue.specreg.site.eligibility");
	}
	
	protected String getSpecialRegistrationApiSiteSubmit() {
		return ApplicationProperties.getProperty("purdue.specreg.site.submit");
	}
	
	protected String getSpecialRegistrationApiSiteGetAll() {
		return ApplicationProperties.getProperty("purdue.specreg.site.getall", getSpecialRegistrationApiSiteSubmit());
	}
	
	protected String getSpecialRegistrationApiSiteCheck() {
		return ApplicationProperties.getProperty("purdue.specreg.site.check", getSpecialRegistrationApiSiteGetAll());
	}
	
	protected String getSpecialRegistrationApiUser() {
		return ApplicationProperties.getProperty("purdue.specreg.user");
	}
	
	protected String getSpecialRegistrationApiPassword() {
		return ApplicationProperties.getProperty("purdue.specreg.password");
	}
	
	protected String getSpecialRegistrationApiKeyParameter() {
		return ApplicationProperties.getProperty("purdue.specreg.apikey.name");
	}
	
	protected String getSpecialRegistrationApiKeyValue() {
		return ApplicationProperties.getProperty("purdue.specreg.apikey.value");
	}
	
	protected String getBannerTerm(AcademicSessionInfo session) {
		return iExternalTermProvider.getExternalTerm(session);
	}
	
	protected String getBannerCampus(AcademicSessionInfo session) {
		return iExternalTermProvider.getExternalCampus(session);
	}
	
	protected String getBannerId(XStudent student) {
		String id = student.getExternalId();
		while (id.length() < 9) id = "0" + id;
		return id;
	}
	
	protected List<Change> buildChangeList(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, Collection<ClassAssignmentInterface.ClassAssignment> assignment, Collection<ErrorMessage> errors) {
		List<Change> changes = new ArrayList<Change>();
		Map<XCourse, List<XSection>> enrollments = new HashMap<XCourse, List<XSection>>();
		Map<Long, XOffering> offerings = new HashMap<Long, XOffering>();
		for (ClassAssignmentInterface.ClassAssignment ca: assignment) {
			// Skip free times and dummy sections
			if (ca == null || ca.isFreeTime() || ca.getClassId() == null || ca.isDummy() || ca.isTeachingAssignment()) continue;
			
			XCourse course = server.getCourse(ca.getCourseId());
			if (course == null)
				throw new SectioningException(MSG.exceptionCourseDoesNotExist(MSG.courseName(ca.getSubject(), ca.getClassNumber())));
			XOffering offering = server.getOffering(course.getOfferingId());
			if (offering == null)
				throw new SectioningException(MSG.exceptionCourseDoesNotExist(MSG.courseName(ca.getSubject(), ca.getClassNumber())));
			
			// Check section limits
			XSection section = offering.getSection(ca.getClassId());
			if (section == null)
				throw new SectioningException(MSG.exceptionEnrollNotAvailable(MSG.clazz(ca.getSubject(), ca.getCourseNbr(), ca.getSubpart(), ca.getSection())));
			
			// Check cancelled flag
			if (section.isCancelled()) {
				if (server.getConfig().getPropertyBoolean("Enrollment.CanKeepCancelledClass", false)) {
					boolean contains = false;
					for (XRequest r: student.getRequests())
						if (r instanceof XCourseRequest) {
							XCourseRequest cr = (XCourseRequest)r;
							if (cr.getEnrollment() != null && cr.getEnrollment().getSectionIds().contains(section.getSectionId())) { contains = true; break; }
						}
					if (!contains)
						throw new SectioningException(MSG.exceptionEnrollCancelled(MSG.clazz(ca.getSubject(), ca.getCourseNbr(), ca.getSubpart(), ca.getSection())));
				} else {
					throw new SectioningException(MSG.exceptionEnrollCancelled(MSG.clazz(ca.getSubject(), ca.getCourseNbr(), ca.getSubpart(), ca.getSection())));
				}
			}
			
			List<XSection> sections = enrollments.get(course);
			if (sections == null) {
				sections = new ArrayList<XSection>();
				enrollments.put(course, sections);
			}
			sections.add(section);
			offerings.put(course.getCourseId(), offering);
		}
		Set<String> crns = new HashSet<String>();
		check: for (Map.Entry<XCourse, List<XSection>> e: enrollments.entrySet()) {
			XCourse course = e.getKey();
			List<XSection> sections = e.getValue();

			for (XRequest r: student.getRequests()) {
				if (r instanceof XCourseRequest) {
					XEnrollment enrollment = ((XCourseRequest)r).getEnrollment();
					if (enrollment != null && enrollment.getCourseId().equals(course.getCourseId())) { // course change
						for (XSection s: sections) {
							if (!enrollment.getSectionIds().contains(s.getSectionId())) {
								Change ch = new Change();
								ch.subject = course.getSubjectArea();
								ch.courseNbr = course.getCourseNumber();
								ch.crn = s.getExternalId(course.getCourseId());
								ch.operation = ChangeOperation.ADD;
								if (crns.add(ch.crn)) changes.add(ch);
							}
						}
						for (Long id: enrollment.getSectionIds()) {
							XSection s = offerings.get(course.getCourseId()).getSection(id);
							if (!sections.contains(s)) {
								Change ch = new Change();
								ch.subject = course.getSubjectArea();
								ch.courseNbr = course.getCourseNumber();
								ch.crn = s.getExternalId(course.getCourseId());
								ch.operation = ChangeOperation.DROP;
								if (crns.add(ch.crn)) changes.add(ch);
							}
						}
						continue check;
					}
				}
			}
			
			// new course
			for (XSection section: sections) {
				Change ch = new Change();
				ch.subject = course.getSubjectArea();
				ch.courseNbr = course.getCourseNumber();
				ch.crn = section.getExternalId(course.getCourseId());
				ch.operation = ChangeOperation.ADD;
				if (crns.add(ch.crn)) changes.add(ch);
			}
		}
		
		// drop course
		for (XRequest r: student.getRequests()) {
			if (r instanceof XCourseRequest) {
				XEnrollment enrollment = ((XCourseRequest)r).getEnrollment();
				if (enrollment != null && !offerings.containsKey(enrollment.getCourseId())) {
					XOffering offering = server.getOffering(enrollment.getOfferingId());
					if (offering != null)
						for (XSection section: offering.getSections(enrollment)) {
							XCourse course = offering.getCourse(enrollment.getCourseId());
							Change ch = new Change();
							ch.subject = course.getSubjectArea();
							ch.courseNbr = course.getCourseNumber();
							ch.crn = section.getExternalId(course.getCourseId());
							ch.operation = ChangeOperation.DROP;
							changes.add(ch);
						}
				}
			}
		}
		
		if (errors != null) {
			Set<ErrorMessage> added = new HashSet<ErrorMessage>();
			for (Change ch: changes) {
				for (ErrorMessage m: errors)
					if (ch.crn.equals(m.getSection()) && added.add(m)) {
						if (ch.errors == null) ch.errors = new ArrayList<ChangeError>();
						ChangeError er = new ChangeError();
						er.code = m.getCode();
						er.message = m.getMessage();
						ch.errors.add(er);
					}
			}
		}
		
		return changes;
	}

	@Override
	public SpecialRegistrationEligibilityResponse checkEligibility(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, SpecialRegistrationEligibilityRequest input) throws SectioningException {
		if (student == null) return new SpecialRegistrationEligibilityResponse(false, "No student.");
		ClientResource resource = null;
		try {
			Gson gson = getGson(helper);
			SpecialRegistrationRequest request = new SpecialRegistrationRequest();
			AcademicSessionInfo session = server.getAcademicSession();
			request.term = getBannerTerm(session);
			request.campus = getBannerCampus(session);
			request.studentId = getBannerId(student);
			request.changes = buildChangeList(server, helper, student, input.getClassAssignments(), input.getErrors());

			resource = new ClientResource(getSpecialRegistrationApiSiteCheckEligibility());
			resource.setNext(iClient);
			String apiKeyName = getSpecialRegistrationApiKeyParameter();
			if (apiKeyName != null)
				resource.addQueryParameter(apiKeyName, getSpecialRegistrationApiKeyValue());
			String apiUser = getSpecialRegistrationApiUser();
			if (apiUser != null)
				resource.setChallengeResponse(ChallengeScheme.HTTP_BASIC, apiUser, getSpecialRegistrationApiPassword());
			
			if (helper.isDebugEnabled())
				helper.debug("Request: " + gson.toJson(request));
			helper.getAction().addOptionBuilder().setKey("request").setValue(gson.toJson(request));
			
			long t1 = System.currentTimeMillis();
			
			resource.post(new GsonRepresentation<SpecialRegistrationRequest>(request));
			
			helper.getAction().setApiPostTime(System.currentTimeMillis() - t1);
			
			SpecialRegistrationResponse response = (SpecialRegistrationResponse)new GsonRepresentation<SpecialRegistrationResponse>(resource.getResponseEntity(), SpecialRegistrationResponse.class).getObject();
			
			if (helper.isDebugEnabled())
				helper.debug("Response: " + gson.toJson(response));
			helper.getAction().addOptionBuilder().setKey("response").setValue(gson.toJson(response));
			
			return new SpecialRegistrationEligibilityResponse(response != null && response.status == SpecialRegistrationResponseStatus.success, response != null ? response.message : null);
		} catch (Exception e) {
			helper.getAction().setApiException(e.getMessage());
			throw new SectioningException("Failed to check special registration eligibility: " + e.getMessage());
		} finally {
			if (resource != null) {
				if (resource.getResponse() != null) resource.getResponse().release();
				resource.release();
			}
		}
	}

	@Override
	public SubmitSpecialRegistrationResponse submitRegistration(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, SubmitSpecialRegistrationRequest input) throws SectioningException {
		ClientResource resource = null;
		try {
			SpecialRegistrationRequest request = new SpecialRegistrationRequest();
			AcademicSessionInfo session = server.getAcademicSession();
			request.term = getBannerTerm(session);
			request.campus = getBannerCampus(session);
			request.studentId = getBannerId(student);
			request.changes = buildChangeList(server, helper, student, input.getClassAssignments(), input.getErrors());
			request.requestId = input.getRequestId();

			resource = new ClientResource(getSpecialRegistrationApiSiteSubmit());
			resource.setNext(iClient);
			String apiKeyName = getSpecialRegistrationApiKeyParameter();
			if (apiKeyName != null)
				resource.addQueryParameter(apiKeyName, getSpecialRegistrationApiKeyValue());
			String apiUser = getSpecialRegistrationApiUser();
			if (apiUser != null)
				resource.setChallengeResponse(ChallengeScheme.HTTP_BASIC, apiUser, getSpecialRegistrationApiPassword());
			
			Gson gson = getGson(helper);
			if (helper.isDebugEnabled())
				helper.debug("Request: " + gson.toJson(request));
			helper.getAction().addOptionBuilder().setKey("request").setValue(gson.toJson(request));
			long t1 = System.currentTimeMillis();
			
			resource.post(new GsonRepresentation<SpecialRegistrationRequest>(request));
			
			helper.getAction().setApiPostTime(System.currentTimeMillis() - t1);
			
			SpecialRegistrationResponse response = (SpecialRegistrationResponse)new GsonRepresentation<SpecialRegistrationResponse>(resource.getResponseEntity(), SpecialRegistrationResponse.class).getObject();
			if (helper.isDebugEnabled())
				helper.debug("Response: " + gson.toJson(response));
			helper.getAction().addOptionBuilder().setKey("response").setValue(gson.toJson(response));
			
			SubmitSpecialRegistrationResponse ret = new SubmitSpecialRegistrationResponse();
			ret.setRequestId(response.requestId);
			ret.setMessage(response.message);
			ret.setCanEnroll(response.requestStatus == SpecialRegistrationStatus.maySubmit);
			ret.setCanSubmit(response.requestStatus == SpecialRegistrationStatus.mayEdit);
			ret.setSuccess(response.status == SpecialRegistrationResponseStatus.success);
			return ret;
		} catch (Exception e) {
			helper.getAction().setApiException(e.getMessage());
			sLog.error("Failed to submit special registration: " + e.getMessage(), e);
			throw new SectioningException("Failed to submit special registration: " + e.getMessage());
		} finally {
			if (resource != null) {
				if (resource.getResponse() != null) resource.getResponse().release();
				resource.release();
			}
		}
	}

	@Override
	public void dispose() {
		try {
			iClient.stop();
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
		}	
	}
	
	protected CourseOffering findCourseByExternalId(Long sessionId, String externalId) {
		return iExternalClassLookup.findCourseByExternalId(sessionId, externalId);
	}
	
	protected List<Class_> findClassesByExternalId(Long sessionId, String externalId) {
		return iExternalClassLookup.findClassesByExternalId(sessionId, externalId);
	}
	
	protected boolean isDrop(XEnrollment enrollment,  List<Change> changes) {
		return false;
	}
	
	protected List<XRequest> getRequests(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, Map<CourseOffering, List<Class_>> adds, Map<CourseOffering, List<Class_>> drops) {
		Student dbStudent = StudentDAO.getInstance().get(student.getStudentId(), helper.getHibSession());
		List<XRequest> requests = new ArrayList<XRequest>();
		Set<CourseOffering> remaining = new HashSet<CourseOffering>(adds.keySet());
		
		for (XRequest request: student.getRequests()) {
			if (request instanceof XCourseRequest) {
				XCourseRequest cr = (XCourseRequest)request;
				List<Class_> add = null;
				List<Class_> drop = null;
				XCourseId courseId = null;
				Long configId = null;
				for (XCourseId course: ((XCourseRequest)request).getCourseIds()) {
					for (Map.Entry<CourseOffering, List<Class_>> e: adds.entrySet()) 
						if (course.getCourseId().equals(e.getKey().getUniqueId())) {
							add = e.getValue();
							courseId = course;
							configId = e.getValue().iterator().next().getSchedulingSubpart().getInstrOfferingConfig().getUniqueId();
							remaining.remove(e.getKey());
						}
					for (Map.Entry<CourseOffering, List<Class_>> e: drops.entrySet()) 
						if (course.getCourseId().equals(e.getKey().getUniqueId())) {
							drop = e.getValue();
						}
				}
				if (add == null && drop == null) {
					// no change detected
					requests.add(request);
				} else {
					XEnrollment enrollemnt = cr.getEnrollment();
					Set<Long> classIds = (enrollemnt == null ? new HashSet<Long>() : new HashSet<Long>(enrollemnt.getSectionIds()));
					if (enrollemnt != null) {
						if (courseId != null) { // add -> check course & config
							if (!enrollemnt.getCourseId().equals(courseId.getCourseId()) && drop == null) {
								// different course and no drop -> create new course request
								requests.add(request);
								remaining.add(CourseOfferingDAO.getInstance().get(courseId.getCourseId(), helper.getHibSession()));
								continue;
							} else if (!enrollemnt.getConfigId().equals(configId)) {
								// same course different config -> drop all
								classIds.clear();
							}
						} else {
							courseId = enrollemnt;
							configId = enrollemnt.getConfigId();
						}
					}
					if (add != null)
						for (Class_ c: add) classIds.add(c.getUniqueId());
					if (drop != null)
						for (Class_ c: drop) classIds.remove(c.getUniqueId());
					if (classIds.isEmpty()) {
						requests.add(new XCourseRequest(cr, null));
					} else {
						requests.add(new XCourseRequest(cr, new XEnrollment(dbStudent, courseId, configId, classIds)));
					}
				}
			} else {
				// free time --> no change
				requests.add(request);
			}
		}
		for (CourseOffering course: remaining) {
			Long configId = null;
			Set<Long> classIds = new HashSet<Long>();
			for (Class_ clazz: adds.get(course)) {
				if (configId == null) configId = clazz.getSchedulingSubpart().getInstrOfferingConfig().getUniqueId();
				classIds.add(clazz.getUniqueId());
			}
			XCourseId courseId = new XCourseId(course);
			requests.add(new XCourseRequest(dbStudent, courseId, requests.size(), new XEnrollment(dbStudent, courseId, configId, classIds)));
		}
		return requests;
	}
	
	protected Set<ErrorMessage> checkRequests(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, List<XRequest> xrequests) {
		Set<ErrorMessage> errors = new TreeSet<ErrorMessage>();
		List<EnrollmentRequest> requests = new ArrayList<EnrollmentRequest>();
		Hashtable<Long, XOffering> courseId2offering = new Hashtable<Long, XOffering>();
		for (XRequest req: xrequests) {
			if (!(req instanceof XCourseRequest)) continue;
			XCourseRequest courseReq = (XCourseRequest)req;
			XEnrollment e = courseReq.getEnrollment();
			if (e == null) continue;
			XCourse course = server.getCourse(e.getCourseId());
			if (course == null)
				throw new SectioningException(MSG.exceptionCourseDoesNotExist(e.getCourseName()));
			EnrollmentRequest request = new EnrollmentRequest(course, new ArrayList<XSection>());
			requests.add(request);
			XOffering offering = server.getOffering(course.getOfferingId());
			if (offering == null)
				throw new SectioningException(MSG.exceptionCourseDoesNotExist(e.getCourseName()));
			for (Long sectionId: e.getSectionIds()) {
				// Check section limits
				XSection section = offering.getSection(sectionId);
				if (section == null)
					throw new SectioningException(MSG.exceptionEnrollNotAvailable(e.getCourseName() + " " + sectionId));
				
				// Check cancelled flag
				if (section.isCancelled()) {
					if (server.getConfig().getPropertyBoolean("Enrollment.CanKeepCancelledClass", false)) {
						boolean contains = false;
						for (XRequest r: student.getRequests())
							if (r instanceof XCourseRequest) {
								XCourseRequest cr = (XCourseRequest)r;
								if (cr.getEnrollment() != null && cr.getEnrollment().getSectionIds().contains(section.getSectionId())) { contains = true; break; }
							}
						if (!contains)
							errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_CANCEL, MSG.exceptionEnrollCancelled(MSG.clazz(course.getSubjectArea(), course.getCourseNumber(), section.getSubpartName(), section.getName(course.getCourseId())))));
					} else
						errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_CANCEL, MSG.exceptionEnrollCancelled(MSG.clazz(course.getSubjectArea(), course.getCourseNumber(), section.getSubpartName(), section.getName(course.getCourseId())))));
				}
				request.getSections().add(section);
				courseId2offering.put(course.getCourseId(), offering);
			}
		}
			
		// Check for NEW and CHANGE deadlines
		check: for (EnrollmentRequest request: requests) {
			XCourse course = request.getCourse();
			List<XSection> sections = request.getSections();

			for (XRequest r: student.getRequests()) {
				if (r instanceof XCourseRequest) {
					XEnrollment enrollment = ((XCourseRequest)r).getEnrollment();
					if (enrollment != null && enrollment.getCourseId().equals(course.getCourseId())) { // course change
						for (XSection s: sections)
							if (!enrollment.getSectionIds().contains(s.getSectionId()) && !server.checkDeadline(course.getCourseId(), s.getTime(), OnlineSectioningServer.Deadline.CHANGE))
								errors.add(new ErrorMessage(course.getCourseName(), s.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_DEADLINE, MSG.exceptionEnrollDeadlineChange(MSG.clazz(course.getSubjectArea(), course.getCourseNumber(), s.getSubpartName(), s.getName(course.getCourseId())))));
						continue check;
					}
				}
			}
			
			// new course
			for (XSection section: sections) {
				if (!server.checkDeadline(course.getOfferingId(), section.getTime(), OnlineSectioningServer.Deadline.NEW))
					errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_DEADLINE, MSG.exceptionEnrollDeadlineNew(MSG.clazz(course.getSubjectArea(), course.getCourseNumber(), section.getSubpartName(), section.getName(course.getCourseId())))));
			}
		}
		
		// Check for DROP deadlines
		for (XRequest r: student.getRequests()) {
			if (r instanceof XCourseRequest) {
				XEnrollment enrollment = ((XCourseRequest)r).getEnrollment();
				if (enrollment != null && !courseId2offering.containsKey(enrollment.getCourseId())) {
					XOffering offering = server.getOffering(enrollment.getOfferingId());
					if (offering != null)
						for (XSection section: offering.getSections(enrollment)) {
							if (!server.checkDeadline(offering.getOfferingId(), section.getTime(), OnlineSectioningServer.Deadline.DROP))
								errors.add(new ErrorMessage(enrollment.getCourseName(), section.getExternalId(enrollment.getCourseId()), ErrorMessage.UniTimeCode.UT_DEADLINE, MSG.exceptionEnrollDeadlineDrop(enrollment.getCourseName())));
						}
				}
			}
		}
		
		Hashtable<Long, XConfig> courseId2config = new Hashtable<Long, XConfig>();
		for (EnrollmentRequest request: requests) {
			XCourse course = request.getCourse();
			XOffering offering = courseId2offering.get(course.getCourseId());
			XEnrollments enrollments = server.getEnrollments(course.getOfferingId());
			List<XSection> sections = request.getSections();
			XSubpart subpart = offering.getSubpart(sections.get(0).getSubpartId());
			XConfig config = offering.getConfig(subpart.getConfigId());
			courseId2config.put(course.getCourseId(), config);

			XReservation reservation = null;
			reservations: for (XReservation r: offering.getReservations()) {
				if (!r.isApplicable(student, course)) continue;
				if (r.getLimit() >= 0 && r.getLimit() <= enrollments.countEnrollmentsForReservation(r.getReservationId())) {
					boolean contain = false;
					for (XEnrollment e: enrollments.getEnrollmentsForReservation(r.getReservationId()))
						if (e.getStudentId().equals(student.getStudentId())) { contain = true; break; }
					if (!contain) continue;
				}
				if (!r.getConfigsIds().isEmpty() && !r.getConfigsIds().contains(config.getConfigId())) continue;
				for (XSection section: sections)
					if (r.getSectionIds(section.getSubpartId()) != null && !r.getSectionIds(section.getSubpartId()).contains(section.getSectionId())) continue reservations;
				if (reservation == null || r.compareTo(reservation) < 0)
					reservation = r;
			}
			
			if (reservation == null || !reservation.canAssignOverLimit()) {
				for (XSection section: sections) {
					if (section.getLimit() >= 0 && section.getLimit() <= enrollments.countEnrollmentsForSection(section.getSectionId())) {
						boolean contain = false;
						for (XEnrollment e: enrollments.getEnrollmentsForSection(section.getSectionId()))
							if (e.getStudentId().equals(student.getStudentId())) { contain = true; break; }
						if (!contain)
							errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_NOT_AVAILABLE, MSG.exceptionEnrollNotAvailable(MSG.clazz(course.getSubjectArea(), course.getCourseNumber(), section.getSubpartName(), section.getName()))));
					}
					if ((reservation == null || !offering.getSectionReservations(section.getSectionId()).contains(reservation)) && offering.getUnreservedSectionSpace(section.getSectionId(), enrollments) <= 0) {
						boolean contain = false;
						for (XEnrollment e: enrollments.getEnrollmentsForSection(section.getSectionId()))
							if (e.getStudentId().equals(student.getStudentId())) { contain = true; break; }
						if (!contain)
							errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_NOT_AVAILABLE, MSG.exceptionEnrollNotAvailable(MSG.clazz(course.getSubjectArea(), course.getCourseNumber(), section.getSubpartName(), section.getName()))));
					}
				}
				
				if (config.getLimit() >= 0 && config.getLimit() <= enrollments.countEnrollmentsForConfig(config.getConfigId())) {
					boolean contain = false;
					for (XEnrollment e: enrollments.getEnrollmentsForConfig(config.getConfigId()))
						if (e.getStudentId().equals(student.getStudentId())) { contain = true; break; }
					if (!contain)
						for (XSection section: sections)
							errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_NOT_AVAILABLE, MSG.exceptionEnrollNotAvailable(MSG.courseName(course.getSubjectArea(), course.getCourseNumber())) + " " + config.getName()));
				}
				if ((reservation == null || !offering.getConfigReservations(config.getConfigId()).contains(reservation)) && offering.getUnreservedConfigSpace(config.getConfigId(), enrollments) <= 0) {
					boolean contain = false;
					for (XEnrollment e: enrollments.getEnrollmentsForConfig(config.getConfigId()))
						if (e.getStudentId().equals(student.getStudentId())) { contain = true; break; }
					if (!contain)
						for (XSection section: sections)
							errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_NOT_AVAILABLE, MSG.exceptionEnrollNotAvailable(MSG.courseName(course.getSubjectArea(), course.getCourseNumber())) + " " + config.getName()));
				}
				
				if (course.getLimit() >= 0 && course.getLimit() <= enrollments.countEnrollmentsForCourse(course.getCourseId())) {
					boolean contain = false;
					for (XEnrollment e: enrollments.getEnrollmentsForCourse(course.getCourseId()))
						if (e.getStudentId().equals(student.getStudentId())) { contain = true; break; }
					if (!contain)
						for (XSection section: sections)
							errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_NOT_AVAILABLE, MSG.exceptionEnrollNotAvailable(MSG.courseName(course.getSubjectArea(), course.getCourseNumber())) + " " + config.getName()));
				}
			}
		}
		
		for (EnrollmentRequest request: requests) {
			XCourse course = request.getCourse();
			XOffering offering = courseId2offering.get(course.getCourseId());
			List<XSection> sections = request.getSections();
			XSubpart subpart = offering.getSubpart(sections.get(0).getSubpartId());
			XConfig config = offering.getConfig(subpart.getConfigId());
			if (sections.size() < config.getSubparts().size()) {
				for (XSection section: sections)
					errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_STRUCTURE, MSG.exceptionEnrollmentIncomplete(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
			} else if (sections.size() > config.getSubparts().size()) {
				for (XSection section: sections)
					errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_STRUCTURE, MSG.exceptionEnrollmentInvalid(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
			}
			for (XSection s1: sections) {
				for (XSection s2: sections) {
					if (s1.getSectionId() < s2.getSectionId() && s1.isOverlapping(offering.getDistributions(), s2)) {
						errors.add(new ErrorMessage(course.getCourseName(), s1.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_TIME_CNF, MSG.exceptionEnrollmentOverlapping(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
						errors.add(new ErrorMessage(course.getCourseName(), s2.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_TIME_CNF, MSG.exceptionEnrollmentOverlapping(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
					}
					if (!s1.getSectionId().equals(s2.getSectionId()) && s1.getSubpartId().equals(s2.getSubpartId())) {
						errors.add(new ErrorMessage(course.getCourseName(), s1.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_STRUCTURE, MSG.exceptionEnrollmentInvalid(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
						errors.add(new ErrorMessage(course.getCourseName(), s2.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_STRUCTURE, MSG.exceptionEnrollmentInvalid(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
					}
				}
				if (!offering.getSubpart(s1.getSubpartId()).getConfigId().equals(config.getConfigId()))
					errors.add(new ErrorMessage(course.getCourseName(), s1.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_STRUCTURE, MSG.exceptionEnrollmentInvalid(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
			}
			if (!offering.isAllowOverlap(student, config.getConfigId(), course, sections))
				for (EnrollmentRequest otherRequest: requests) {
					XOffering other = courseId2offering.get(otherRequest.getCourse().getCourseId());
					XConfig otherConfig = courseId2config.get(otherRequest.getCourse().getCourseId());
					if (!other.equals(offering) && !other.isAllowOverlap(student, otherConfig.getConfigId(), otherRequest.getCourse(), otherRequest.getSections())) {
						List<XSection> assignment = otherRequest.getSections();
						for (XSection section: sections)
							if (section.isOverlapping(offering.getDistributions(), assignment))
								errors.add(new ErrorMessage(course.getCourseName(), section.getExternalId(course.getCourseId()), ErrorMessage.UniTimeCode.UT_TIME_CNF,MSG.exceptionEnrollmentConflicting(MSG.courseName(course.getSubjectArea(), course.getCourseNumber()))));
					}
				}
		}
		return errors;
	}
	
	protected RetrieveSpecialRegistrationResponse convert(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, SpecialRegistrationRequest specialRequest) {
		Map<CourseOffering, List<Class_>> adds = new HashMap<CourseOffering, List<Class_>>();
		Map<CourseOffering, List<Class_>> drops = new HashMap<CourseOffering, List<Class_>>();
		TreeSet<CourseOffering> courses = new TreeSet<CourseOffering>();
		if (specialRequest.changes != null)
			for (Change change: specialRequest.changes) {
				CourseOffering course = findCourseByExternalId(server.getAcademicSession().getUniqueId(), change.crn);
				List<Class_> classes = findClassesByExternalId(server.getAcademicSession().getUniqueId(), change.crn);
				if (course != null && classes != null && !classes.isEmpty()) {
					courses.add(course);
					List<Class_> list = (change.operation == ChangeOperation.ADD ? adds : drops).get(course);
					if (list == null) {
						list = new ArrayList<Class_>();
						 (change.operation == ChangeOperation.ADD ? adds : drops).put(course, list);
					}
					for (Class_ clazz: classes)
						list.add(clazz);
				}
			}
		String desc = "";
		for (CourseOffering course: courses) {
			if (!desc.isEmpty()) desc += ", ";
			desc += course.getCourseName();
			if (adds.containsKey(course)) {
				if (drops.containsKey(course)) {
					desc += " (change)";
				} else {
					desc += " (add)";
				}
			} else if (drops.containsKey(course)) {
				desc += " (drop)";
			}
		}
		
		RetrieveSpecialRegistrationResponse ret = new RetrieveSpecialRegistrationResponse();
		List<XRequest> requests = getRequests(server, helper, student, adds, drops);
		Set<ErrorMessage> errors = checkRequests(server, helper, student, requests);
		ret.setClassAssignments(GetAssignment.computeAssignment(server, helper, student, requests, null, errors, true));
		ret.setDescription(desc);
		
		if (ret.hasClassAssignments())
			for (CourseAssignment course: ret.getClassAssignments().getCourseAssignments()) {
				if (course.isFreeTime()) continue;
				List<Class_> add = null;
				for (Map.Entry<CourseOffering, List<Class_>> e: adds.entrySet())
					if (course.getCourseId().equals(e.getKey().getUniqueId())) { add = e.getValue(); break; }
				if (add != null)
					for (ClassAssignment ca: course.getClassAssignments())
						if (ca.isSaved())
							for (Class_ c: add)
								if (c.getUniqueId().equals(ca.getClassId())) ca.setSaved(false);
			}
		
		ret.setCanEnroll(specialRequest.status == SpecialRegistrationStatus.maySubmit);
		ret.setCanSubmit(specialRequest.status == SpecialRegistrationStatus.mayEdit);
		ret.setRequestId(specialRequest.requestId);
		ret.setSubmitDate(specialRequest.submitted == null ? null : specialRequest.submitted.toDate());
		return ret;
	}
	
	@Override
	public RetrieveSpecialRegistrationResponse retrieveRegistration(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student, RetrieveSpecialRegistrationRequest input) throws SectioningException {
		if (student == null) return null;
		ClientResource resource = null;
		try {
			resource = new ClientResource(getSpecialRegistrationApiSiteSubmit());
			resource.setNext(iClient);
			AcademicSessionInfo session = server.getAcademicSession();
			String term = getBannerTerm(session);
			String campus = getBannerCampus(session);
			resource.addQueryParameter("term", term);
			resource.addQueryParameter("campus", campus);
			resource.addQueryParameter("studentId", getBannerId(student));
			resource.addQueryParameter("requestId", input.getRequestId());
			helper.getAction().addOptionBuilder().setKey("term").setValue(term);
			helper.getAction().addOptionBuilder().setKey("campus").setValue(campus);
			helper.getAction().addOptionBuilder().setKey("studentId").setValue(getBannerId(student));
			helper.getAction().addOptionBuilder().setKey("requestId").setValue(input.getRequestId());
			String apiKeyName = getSpecialRegistrationApiKeyParameter();
			if (apiKeyName != null)
				resource.addQueryParameter(apiKeyName, getSpecialRegistrationApiKeyValue());
			String apiUser = getSpecialRegistrationApiUser();
			if (apiUser != null)
				resource.setChallengeResponse(ChallengeScheme.HTTP_BASIC, apiUser, getSpecialRegistrationApiPassword());
			
			long t1 = System.currentTimeMillis();
			
			resource.get(MediaType.APPLICATION_JSON);
			
			helper.getAction().setApiGetTime(System.currentTimeMillis() - t1);
			
			SpecialRegistrationRequest specialRequest = (SpecialRegistrationRequest)new GsonRepresentation<SpecialRegistrationRequest>(resource.getResponseEntity(), SpecialRegistrationRequest.class).getObject();
			Gson gson = getGson(helper);
			if (helper.isDebugEnabled())
				helper.debug("Response: " + gson.toJson(specialRequest));
			helper.getAction().addOptionBuilder().setKey("response").setValue(gson.toJson(specialRequest));
			
			return convert(server, helper, student, specialRequest);
		} catch (Exception e) {
			helper.getAction().setApiException(e.getMessage());
			sLog.error("Failed to request special registration: " + e.getMessage(), e);
			throw new SectioningException("Failed to request special registration: " + e.getMessage());
		} finally {
			if (resource != null) {
				if (resource.getResponse() != null) resource.getResponse().release();
				resource.release();
			}
		}
	}
	
	protected Gson getGson(OnlineSectioningHelper helper) {
		GsonBuilder builder = new GsonBuilder()
		.registerTypeAdapter(DateTime.class, new JsonSerializer<DateTime>() {
			@Override
			public JsonElement serialize(DateTime src, Type typeOfSrc, JsonSerializationContext context) {
				return new JsonPrimitive(src.toString("yyyy-MM-dd'T'HH:mm:ss'Z'"));
			}
		})
		.registerTypeAdapter(DateTime.class, new JsonDeserializer<DateTime>() {
			@Override
			public DateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
				return new DateTime(json.getAsJsonPrimitive().getAsString(), DateTimeZone.UTC);
			}
		})
		.registerTypeAdapter(Date.class, new JsonSerializer<Date>() {
			@Override
			public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
				return new JsonPrimitive(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(src));
			}
		})
		.registerTypeAdapter(Date.class, new JsonDeserializer<Date>() {
			@Override
			public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
				try {
					return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(json.getAsJsonPrimitive().getAsString());
				} catch (ParseException e) {
					throw new JsonParseException(e.getMessage(), e);
				}
			}
		});
		if (helper.isDebugEnabled()) builder.setPrettyPrinting();
		return builder.create();
	}

	@Override
	public boolean hasSpecialRegistrationRequests(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student) throws SectioningException {
		if (student == null) return false;
		ClientResource resource = null;
		try {
			resource = new ClientResource(getSpecialRegistrationApiSiteCheck());
			resource.setNext(iClient);
			AcademicSessionInfo session = server.getAcademicSession();
			String term = getBannerTerm(session);
			String campus = getBannerCampus(session);
			resource.addQueryParameter("term", term);
			resource.addQueryParameter("campus", campus);
			resource.addQueryParameter("studentId", getBannerId(student));
			helper.getAction().addOptionBuilder().setKey("term").setValue(term);
			helper.getAction().addOptionBuilder().setKey("campus").setValue(campus);
			helper.getAction().addOptionBuilder().setKey("studentId").setValue(getBannerId(student));
			String apiKeyName = getSpecialRegistrationApiKeyParameter();
			if (apiKeyName != null)
				resource.addQueryParameter(apiKeyName, getSpecialRegistrationApiKeyValue());
			String apiUser = getSpecialRegistrationApiUser();
			if (apiUser != null)
				resource.setChallengeResponse(ChallengeScheme.HTTP_BASIC, apiUser, getSpecialRegistrationApiPassword());
			
			resource.get(MediaType.APPLICATION_JSON);
			
			Gson gson = getGson(helper);
			if (getSpecialRegistrationApiSiteCheck().equals(getSpecialRegistrationApiSiteGetAll())) {
				List<SpecialRegistrationRequest> specialRequests = (List<SpecialRegistrationRequest>)new GsonRepresentation<SpecialRegistrationRequest>(resource.getResponseEntity(), SpecialRegistrationRequest.TYPE_LIST).getObject();
				if (helper.isDebugEnabled())
					helper.debug("Response: " + gson.toJson(specialRequests));
				helper.getAction().addOptionBuilder().setKey("response").setValue(gson.toJson(specialRequests));
				return !specialRequests.isEmpty();
			} else {
				SpecialRegistrationResponse specResponse = (SpecialRegistrationResponse)new GsonRepresentation<SpecialRegistrationResponse>(resource.getResponseEntity(), SpecialRegistrationResponse.class).getObject();
				if (helper.isDebugEnabled())
					helper.debug("Response: " + gson.toJson(specResponse));
				helper.getAction().addOptionBuilder().setKey("response").setValue(gson.toJson(specResponse));
				return specResponse.status == SpecialRegistrationResponseStatus.success;
			}
		} catch (Exception e) {
			sLog.error("Failed to check for open registrations: " + e.getMessage(), e);
			throw new SectioningException("Failed to check for open registrations: " + e.getMessage());
		} finally {
			if (resource != null) {
				if (resource.getResponse() != null) resource.getResponse().release();
				resource.release();
			}
		}
	}

	@Override
	public List<RetrieveSpecialRegistrationResponse> retrieveAllRegistrations(OnlineSectioningServer server, OnlineSectioningHelper helper, XStudent student) throws SectioningException {
		if (student == null) return null;
		ClientResource resource = null;
		try {
			resource = new ClientResource(getSpecialRegistrationApiSiteGetAll());
			resource.setNext(iClient);
			AcademicSessionInfo session = server.getAcademicSession();
			String term = getBannerTerm(session);
			String campus = getBannerCampus(session);
			resource.addQueryParameter("term", term);
			resource.addQueryParameter("campus", campus);
			resource.addQueryParameter("studentId", getBannerId(student));
			helper.getAction().addOptionBuilder().setKey("term").setValue(term);
			helper.getAction().addOptionBuilder().setKey("campus").setValue(campus);
			helper.getAction().addOptionBuilder().setKey("studentId").setValue(getBannerId(student));
			String apiKeyName = getSpecialRegistrationApiKeyParameter();
			if (apiKeyName != null)
				resource.addQueryParameter(apiKeyName, getSpecialRegistrationApiKeyValue());
			String apiUser = getSpecialRegistrationApiUser();
			if (apiUser != null)
				resource.setChallengeResponse(ChallengeScheme.HTTP_BASIC, apiUser, getSpecialRegistrationApiPassword());
			
			long t1 = System.currentTimeMillis();
			
			resource.get(MediaType.APPLICATION_JSON);
			
			helper.getAction().setApiGetTime(System.currentTimeMillis() - t1);
			
			List<SpecialRegistrationRequest> specialRequests = (List<SpecialRegistrationRequest>)new GsonRepresentation<SpecialRegistrationRequest>(resource.getResponseEntity(), SpecialRegistrationRequest.TYPE_LIST).getObject();
			Gson gson = getGson(helper);
			if (helper.isDebugEnabled())
				helper.debug("Response: " + gson.toJson(specialRequests));
			helper.getAction().addOptionBuilder().setKey("response").setValue(gson.toJson(specialRequests));
			
			List<RetrieveSpecialRegistrationResponse> ret = new ArrayList<RetrieveSpecialRegistrationResponse>(specialRequests.size());
			for (SpecialRegistrationRequest specialRequest: specialRequests)
				ret.add(convert(server, helper, student, specialRequest));
			
			return ret;
		} catch (Exception e) {
			helper.getAction().setApiException(e.getMessage());
			sLog.error("Failed to request special registrations: " + e.getMessage(), e);
			throw new SectioningException("Failed to request special registrations: " + e.getMessage());
		} finally {
			if (resource != null) {
				if (resource.getResponse() != null) resource.getResponse().release();
				resource.release();
			}
		}
	}
}
