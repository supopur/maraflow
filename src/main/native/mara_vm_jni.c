// JNI bridge wrapping the unmodified C99 MaraVM firmware (mara_vm.c)
// for 1:1 native-speed simulation inside MaraFlow.

#include "mara_vm.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#define SIM_MAX_DIO 32U
#define SIM_MAX_AI  8U
#define SIM_MAX_AO  8U
#define SIM_MAX_PWM 16U

typedef struct {
    VmInstance_t vm;
    uint8_t      di[SIM_MAX_DIO];
    uint8_t      doOut[SIM_MAX_DIO];
    int32_t      ai[SIM_MAX_AI];
    int32_t      ao[SIM_MAX_AO];
    int32_t      pwmFreq[SIM_MAX_PWM];
    float        pwmDuty[SIM_MAX_PWM];
} NativeVmCtx_t;

// Thread-local active context pointer so the C99 VmHalVtable_t callbacks
// can access virtual I/O without modifying mara_vm.h's hardware-agnostic signature.
static _Thread_local NativeVmCtx_t *g_tls_ctx = NULL;

static uint8_t sim_di_read(uint16_t channel)
{
    if (g_tls_ctx == NULL || channel >= SIM_MAX_DIO) {
        return 0U;
    }
    return g_tls_ctx->di[channel];
}

static void sim_do_write(uint16_t channel, uint8_t val)
{
    if (g_tls_ctx == NULL || channel >= SIM_MAX_DIO) {
        return;
    }
    g_tls_ctx->doOut[channel] = (val != 0U) ? 1U : 0U;
}

static int32_t sim_ai_read(uint16_t channel)
{
    if (g_tls_ctx == NULL || channel >= SIM_MAX_AI) {
        return 0;
    }
    return g_tls_ctx->ai[channel];
}

static void sim_ao_write(uint16_t channel, int32_t val)
{
    if (g_tls_ctx == NULL || channel >= SIM_MAX_AO) {
        return;
    }
    g_tls_ctx->ao[channel] = val;
}

static uint8_t sim_pwm_set(uint16_t channel, int32_t freq_hz, float dutyPct)
{
    if (g_tls_ctx == NULL || channel >= SIM_MAX_PWM) {
        return 0U;
    }
    g_tls_ctx->pwmFreq[channel] = freq_hz;
    g_tls_ctx->pwmDuty[channel] = dutyPct;
    return (dutyPct > 0.0f) ? 1U : 0U;
}

JNIEXPORT jlong JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeCreate(JNIEnv *p_env, jclass cls)
{
    (void)p_env;
    (void)cls;

    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)calloc(1U, sizeof(NativeVmCtx_t));
    if (p_ctx == NULL) {
        return 0;
    }

    VmHalVtable_t hal = {
        .di_read         = sim_di_read,
        .do_write        = sim_do_write,
        .ai_read         = sim_ai_read,
        .ao_write        = sim_ao_write,
        .pwm_set         = sim_pwm_set,
        .time_micros     = NULL,
        .rtc_unix_sec    = NULL,
        .rng_next        = NULL,
        .retentive_write = NULL,
        .retentive_read  = NULL,
        .comm_exec       = NULL
    };

    VM_Init(&p_ctx->vm, &hal);
    return (jlong)(uintptr_t)p_ctx;
}

JNIEXPORT void JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeDestroy(JNIEnv *p_env, jclass cls, jlong handle)
{
    (void)p_env;
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx != NULL) {
        free(p_ctx);
    }
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeLoadImage(JNIEnv *p_env, jclass cls, jlong handle, jbyteArray imageArr)
{
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx == NULL || imageArr == NULL) {
        return (jint)VM_ERR_PARAM;
    }

    jsize len = (*p_env)->GetArrayLength(p_env, imageArr);
    jbyte *p_bytes = (*p_env)->GetByteArrayElements(p_env, imageArr, NULL);
    if (p_bytes == NULL) {
        return (jint)VM_ERR_PARAM;
    }

    VmStatus_t st = VM_LoadImage(&p_ctx->vm, (const uint8_t *)p_bytes, (uint32_t)len);
    (*p_env)->ReleaseByteArrayElements(p_env, imageArr, p_bytes, JNI_ABORT);
    return (jint)st;
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeReset(JNIEnv *p_env, jclass cls, jlong handle)
{
    (void)p_env;
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    return (p_ctx != NULL) ? (jint)VM_Reset(&p_ctx->vm) : (jint)VM_ERR_PARAM;
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeSetState(JNIEnv *p_env, jclass cls, jlong handle, jint state)
{
    (void)p_env;
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    return (p_ctx != NULL) ? (jint)VM_SetState(&p_ctx->vm, (VmRunState_t)state) : (jint)VM_ERR_PARAM;
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeGetState(JNIEnv *p_env, jclass cls, jlong handle)
{
    (void)p_env;
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    return (p_ctx != NULL) ? (jint)VM_GetState(&p_ctx->vm) : (jint)VM_STATE_FAULT;
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeStepThread(JNIEnv *p_env, jclass cls, jlong handle, jint threadId, jlong dtUs)
{
    (void)p_env;
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx == NULL) {
        return (jint)VM_ERR_PARAM;
    }

    g_tls_ctx = p_ctx;
    VmStatus_t st = VM_StepThread(&p_ctx->vm, (uint8_t)threadId, (uint32_t)dtUs);
    g_tls_ctx = NULL;
    return (jint)st;
}

JNIEXPORT jbyteArray JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeReadSlotRaw(JNIEnv *p_env, jclass cls, jlong handle, jint slotId)
{
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx == NULL) {
        return NULL;
    }

    uint8_t buf[VM_MAX_SLOT_BYTES];
    uint8_t len = VM_ReadSlotRaw(&p_ctx->vm, (uint16_t)slotId, buf);
    jbyteArray out = (*p_env)->NewByteArray(p_env, (jsize)len);
    if (out != NULL && len > 0U) {
        (*p_env)->SetByteArrayRegion(p_env, out, 0, (jsize)len, (const jbyte *)buf);
    }
    return out;
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeWriteSlotRaw(JNIEnv *p_env, jclass cls, jlong handle, jint slotId, jbyteArray dataArr)
{
    (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx == NULL || dataArr == NULL) {
        return 0;
    }
    jbyte *p_bytes = (*p_env)->GetByteArrayElements(p_env, dataArr, NULL);
    if (p_bytes == NULL) {
        return 0;
    }
    uint8_t written = VM_WriteSlotRaw(&p_ctx->vm, (uint16_t)slotId, (const uint8_t *)p_bytes);
    (*p_env)->ReleaseByteArrayElements(p_env, dataArr, p_bytes, JNI_ABORT);
    return (jint)written;
}

JNIEXPORT void JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeSetDi(JNIEnv *p_env, jclass cls, jlong handle, jint channel, jboolean val)
{
    (void)p_env; (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx != NULL && channel >= 0 && (uint32_t)channel < SIM_MAX_DIO) {
        p_ctx->di[channel] = val ? 1U : 0U;
    }
}

JNIEXPORT jboolean JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeGetDo(JNIEnv *p_env, jclass cls, jlong handle, jint channel)
{
    (void)p_env; (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx != NULL && channel >= 0 && (uint32_t)channel < SIM_MAX_DIO) {
        return (p_ctx->doOut[channel] != 0U) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeSetAi(JNIEnv *p_env, jclass cls, jlong handle, jint channel, jint val)
{
    (void)p_env; (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx != NULL && channel >= 0 && (uint32_t)channel < SIM_MAX_AI) {
        p_ctx->ai[channel] = (int32_t)val;
    }
}

JNIEXPORT jint JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeGetAo(JNIEnv *p_env, jclass cls, jlong handle, jint channel)
{
    (void)p_env; (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx != NULL && channel >= 0 && (uint32_t)channel < SIM_MAX_AO) {
        return (jint)p_ctx->ao[channel];
    }
    return 0;
}

JNIEXPORT jfloat JNICALL Java_cz_maramat_vm_NativeMaraVm_nativeGetPwmDuty(JNIEnv *p_env, jclass cls, jlong handle, jint channel)
{
    (void)p_env; (void)cls;
    NativeVmCtx_t *p_ctx = (NativeVmCtx_t *)(uintptr_t)handle;
    if (p_ctx != NULL && channel >= 0 && (uint32_t)channel < SIM_MAX_PWM) {
        return (jfloat)p_ctx->pwmDuty[channel];
    }
    return 0.0f;
}
