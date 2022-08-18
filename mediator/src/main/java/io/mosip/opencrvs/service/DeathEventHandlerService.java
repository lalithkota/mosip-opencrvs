package io.mosip.opencrvs.service;

import io.mosip.kernel.core.exception.BaseCheckedException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.HMACUtils2;
import io.mosip.opencrvs.constant.ApiName;
import io.mosip.opencrvs.constant.Constants;
import io.mosip.opencrvs.constant.LoggingConstants;
import io.mosip.opencrvs.dto.BaseEventRequest;
import io.mosip.opencrvs.dto.DecryptedEventDto;
import io.mosip.opencrvs.error.ErrorCode;
import io.mosip.opencrvs.util.LogUtil;
import io.mosip.opencrvs.util.OpencrvsCryptoUtil;
import io.mosip.opencrvs.util.RestTokenUtil;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.env.Environment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class DeathEventHandlerService {

    private static final Logger LOGGER = LogUtil.getLogger(DeathEventHandlerService.class);

    @Value("${mosip.iam.token_endpoint}")
    private String iamTokenEndpoint;
    @Value("${mosip.opencrvs.death.deactivate.status:DEACTIVATED}")
    private String deactivateStatus;
    @Value("${mosip.opencrvs.death.client.id}")
    private String deathClientId;
    @Value("${mosip.opencrvs.death.client.secret}")
    private String deathClientSecret;


    @Autowired
    private Environment env;

    @Autowired
    private Receiver receiver;

    @Autowired
    private RestTokenUtil restTokenUtil;

    public String handleEvent(BaseEventRequest request) throws BaseCheckedException {
        DecryptedEventDto decryptedEventDto = receiver.preProcess(request.getId(), request.toString());
        String uin = getUINFromDecryptedEvent(decryptedEventDto);
        String token = restTokenUtil.getOIDCToken(iamTokenEndpoint, deathClientId, deathClientSecret);

        try{
            String dateTimePattern = env.getProperty(Constants.DATETIME_PATTERN);
            String requestString = "{" +
                "\"id\": \"mosip.id.update\"," +
                "\"version\": \"v1.0\"," +
                "\"requesttime\": \"" + DateUtils.getUTCCurrentDateTimeString(dateTimePattern) + "\"," +
                "\"request\": {" +
                    "\"registrationId\": \"\"," +
                    "\"status\": \"" + deactivateStatus + "\"," +
                    "\"identity\": {" +
                        "\"IDSchemaVersion\": 0.0," +
                        "\"UIN\":\"" + uin + "\"" +
                    "}" +
                "}" +
            "}";
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Cookie", "Authorization="+token);
            HttpEntity<String> requestIdentity = new HttpEntity<>(requestString, headers);
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory());
            String response = restTemplate.patchForObject(env.getProperty(ApiName.IDREPO_IDENTITY), requestIdentity, String.class);
            LOGGER.info(LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::deactivateUIN", "Response from Patch Identity : " + response);
            // handle proper errors from response here
        } catch (RestClientException rce) {
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX, LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::deactivateUIN", "Error Occured", rce);
            throw new BaseCheckedException(ErrorCode.UIN_DEACTIVATE_ERROR_DEATH_EVENT_CODE, ErrorCode.UIN_DEACTIVATE_ERROR_DEATH_EVENT_MESSAGE);
        }
        return "";
    }

    public String getUINFromDecryptedEvent(DecryptedEventDto decryptedEventDto) throws BaseCheckedException{
        try{
            List<DecryptedEventDto.Event.Context.Entry> contextEntries = decryptedEventDto.event.context.get(0).entry;
            DecryptedEventDto.Event.Context.Entry.Resource patient = null;
            for(DecryptedEventDto.Event.Context.Entry entry: contextEntries){
                if("Patient".equals(entry.resource.resourceType)){
                    patient = entry.resource;
                    break;
                }
            }
            if (patient == null){
                throw new BaseCheckedException(ErrorCode.MISSING_UIN_IN_DEATH_EVENT_CODE, ErrorCode.MISSING_UIN_IN_DEATH_EVENT_MESSAGE);
            }
            for(DecryptedEventDto.Event.Context.Entry.Resource.Identifier identifier: patient.identifier){
                if("NATIONAL_ID".equals(identifier.type)){
                    return identifier.value;
                }
            }
            throw new BaseCheckedException(ErrorCode.MISSING_UIN_IN_DEATH_EVENT_CODE, ErrorCode.MISSING_UIN_IN_DEATH_EVENT_MESSAGE);
        } catch(NullPointerException ne){
            LOGGER.error(LoggingConstants.FORMATTER_PREFIX, LoggingConstants.SESSION, LoggingConstants.ID, "DeathEvent::getUIN", "Error getting UIN from death event", ne);
            throw new BaseCheckedException(ErrorCode.MISSING_UIN_IN_DEATH_EVENT_CODE, ErrorCode.MISSING_UIN_IN_DEATH_EVENT_MESSAGE, ne);
        }
    }
}
