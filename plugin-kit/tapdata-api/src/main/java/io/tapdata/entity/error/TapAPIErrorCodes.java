package io.tapdata.entity.error;

public class TapAPIErrorCodes {
    public static final int ERROR_TAP_TYPE_MISSING_ON_FIELD = 1000;
    public static final int ERROR_MISSING_JAVA_TYPE = 1001;
    public static final int ERROR_UNKNOWN_JAVA_TYPE = 1002;
    public static final int ERROR_ILLEGAL_PARAMETERS = 1003;
	public static final int ERROR_MISSING_PRIMARY_VALUE = 1004;
	public static final int ERROR_NO_PRIMARY_KEYS = 1005;
	public static final int ERROR_GET_IMPL_CLASS_FAILED = 1006;
	public static final int ERROR_CREATE_CLASS_WITH_TYPE_FAILED = 1007;
	public static final int ERROR_CREATE_CLASS_FAILED = 1008;
    public static final int NEED_RETRY_FAILED = 1009;
	public static final int ERROR_ALL_IPS_FAILED = 1010;
	public static final int ERROR_UNKNOWN_TAP_TYPE = 1011;
	public static final int ERROR_ILLEGAL_DATETIME_ORIGIN_TYPE = 1012;
	public static final int ERROR_FIND_CONNECTOR_TYPE_FAILED = 1013;
	public static final int ERROR_JAVA_CUSTOM_DESERIALIZE_FAILED = 1014;
	public static final int MIN_MAX_CANNOT_CONVERT_TO_DATETIME = 1015;
	public static final int ERROR_PARTITION_FILTER_NULL = 1016;
	public static final int ILLEGAL_OPERATOR_FOR_LEFT_BOUNDARY = 1017;
	public static final int ERROR_INSTANTIATE_ENGINE_CLASS_FAILED = 1018;
}
