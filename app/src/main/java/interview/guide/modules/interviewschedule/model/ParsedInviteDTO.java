package interview.guide.modules.interviewschedule.model;

/** AI 提取的面试邀约信息。 */
public record ParsedInviteDTO(
    String companyName,
    String position,
    String interviewTime,
    String interviewType,
    String meetingLink,
    Integer roundNumber,
    String interviewer,
    String notes
) {}
