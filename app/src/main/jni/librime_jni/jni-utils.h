/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <jni.h>

#include <string>

static inline void throwJavaException(JNIEnv* env, const char* msg) {
  jclass c = env->FindClass("java/lang/Exception");
  env->ThrowNew(c, msg);
  env->DeleteLocalRef(c);
}

class CString {
 private:
  JNIEnv* env_;
  jstring str_;
  const char* chr_;

 public:
  CString(JNIEnv* env, jstring str)
      : env_(env), str_(str), chr_(env->GetStringUTFChars(str, nullptr)) {}

  ~CString() { env_->ReleaseStringUTFChars(str_, chr_); }

  operator std::string() { return chr_; }

  operator const char*() { return chr_; }

  const char* operator*() { return chr_; }
};

template <typename T = jobject>
class JRef {
 private:
  JNIEnv* env_;
  T ref_;

 public:
  JRef(JNIEnv* env, jobject ref) : env_(env), ref_(reinterpret_cast<T>(ref)) {}

  ~JRef() { env_->DeleteLocalRef(ref_); }

  operator T() { return ref_; }

  T operator*() { return ref_; }
};

class JString {
 private:
  JNIEnv* env_;
  jstring jstring_;

 public:
  JString(JNIEnv* env, const char* chars)
      : env_(env), jstring_(env->NewStringUTF(chars)) {}

  JString(JNIEnv* env, const std::string& string)
      : JString(env, string.c_str()) {}

  ~JString() { env_->DeleteLocalRef(jstring_); }

  operator jstring() { return jstring_; }

  jstring operator*() { return jstring_; }
};

class JEnv {
 private:
  JNIEnv* env = nullptr;

 public:
  explicit JEnv(JavaVM* jvm) {
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) ==
        JNI_EDETACHED) {
      jvm->AttachCurrentThread(&env, nullptr);
    }
  }

  operator JNIEnv*() { return env; }

  JNIEnv* operator->() { return env; }
};

class GlobalRefSingleton {
 public:
  JavaVM* jvm;

  jclass Object;

  jclass String;

  jclass Integer;
  jmethodID IntegerInit;

  jclass Boolean;
  jmethodID BooleanInit;

  jclass Rime;
  jmethodID HandleRimeMessage;

  jclass CandidateProto;
  jmethodID CandidateProtoInit;

  jclass CommitProto;
  jmethodID CommitProtoInit;

  jclass ContextProto;
  jmethodID ContextProtoInit;

  jclass CompositionProto;
  jmethodID CompositionProtoInit;

  jclass StatusProto;
  jmethodID StatusProtoInit;

  jclass RimeResponse;
  jmethodID RimeResponseInit;

  jclass CandidatesPaged;
  jmethodID CandidatesPagedInit;

  jclass CandidatesBulk;
  jmethodID CandidatesBulkInit;

  jclass SchemaListItem;
  jmethodID SchemaListItemInit;

  jclass KeyEvent;
  jmethodID KeyEventInit;

  explicit GlobalRefSingleton(JavaVM* jvm_) : jvm(jvm_) {
    JNIEnv* env;
    jvm->AttachCurrentThread(&env, nullptr);

    Object = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/Object")));

    String = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/String")));

    Integer = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/Integer")));
    IntegerInit = env->GetMethodID(Integer, "<init>", "(I)V");

    Boolean = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("java/lang/Boolean")));
    BooleanInit = env->GetMethodID(Boolean, "<init>", "(Z)V");

    Rime = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("net/guyii/ime/core/Rime")));
    HandleRimeMessage = env->GetStaticMethodID(Rime, "handleRimeMessage",
                                               "(I[Ljava/lang/Object;)V");

    CandidateProto = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/CandidateProto")));
    CandidateProtoInit = env->GetMethodID(
        CandidateProto, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

    CommitProto = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("net/guyii/ime/core/CommitProto")));
    CommitProtoInit =
        env->GetMethodID(CommitProto, "<init>", "(Ljava/lang/String;)V");

    ContextProto = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/ContextProto")));
    ContextProtoInit =
        env->GetMethodID(ContextProto, "<init>",
                         "(Lnet/guyii/ime/core/CompositionProto;Ljava/lang/"
                         "String;I)V");

    CompositionProto = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/CompositionProto")));
    CompositionProtoInit =
        env->GetMethodID(CompositionProto, "<init>",
                         "(IIIILjava/lang/String;Ljava/lang/String;)V");

    StatusProto = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("net/guyii/ime/core/StatusProto")));
    StatusProtoInit =
        env->GetMethodID(StatusProto, "<init>",
                         "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");

    RimeResponse = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/RimeResponse")));
    RimeResponseInit = env->GetMethodID(
        RimeResponse, "<init>",
        "(Lnet/guyii/ime/core/CommitProto;Lnet/guyii/ime/core/"
        "CompositionProto;Lnet/guyii/ime/core/Candidates;"
        "Lnet/guyii/ime/core/StatusProto;)V");

    CandidatesPaged = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/Candidates$Paged")));
    CandidatesPagedInit =
        env->GetMethodID(CandidatesPaged, "<init>",
                         "(ZZZI[Lnet/guyii/ime/core/CandidateProto;)V");

    CandidatesBulk = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/Candidates$Bulk")));
    CandidatesBulkInit =
        env->GetMethodID(CandidatesBulk, "<init>",
                         "(II[Lnet/guyii/ime/core/CandidateProto;)V");

    SchemaListItem = reinterpret_cast<jclass>(
        env->NewGlobalRef(env->FindClass("net/guyii/ime/core/SchemaItem")));
    SchemaListItemInit = env->GetMethodID(
        SchemaListItem, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

    KeyEvent = reinterpret_cast<jclass>(env->NewGlobalRef(
        env->FindClass("net/guyii/ime/core/RimeKeyEvent")));
    KeyEventInit =
        env->GetMethodID(KeyEvent, "<init>", "(IILjava/lang/String;)V");
  }

  [[nodiscard]] JEnv AttachEnv() const { return JEnv(jvm); }
};

extern GlobalRefSingleton* GlobalRef;
