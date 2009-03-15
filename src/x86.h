/* Copyright (c) 2008-2009, Avian Contributors

   Permission to use, copy, modify, and/or distribute this software
   for any purpose with or without fee is hereby granted, provided
   that the above copyright notice and this permission notice appear
   in all copies.

   There is NO WARRANTY for this software.  See license.txt for
   details. */

#ifndef X86_H
#define X86_H

#include "types.h"
#include "common.h"

#ifdef __i386__

#  ifdef __APPLE__
#    if __DARWIN_UNIX03 && defined(_STRUCT_X86_EXCEPTION_STATE32)
#      define IP_REGISTER(context) (context->uc_mcontext->__ss.__eip)
#      define BASE_REGISTER(context) (context->uc_mcontext->__ss.__ebp)
#      define STACK_REGISTER(context) (context->uc_mcontext->__ss.__esp)
#      define THREAD_REGISTER(context) (context->uc_mcontext->__ss.__ebx)
#    else
#      define IP_REGISTER(context) (context->uc_mcontext->ss.eip)
#      define BASE_REGISTER(context) (context->uc_mcontext->ss.ebp)
#      define STACK_REGISTER(context) (context->uc_mcontext->ss.esp)
#      define THREAD_REGISTER(context) (context->uc_mcontext->ss.ebx)
#    endif
#  else
#    define IP_REGISTER(context) (context->uc_mcontext.gregs[REG_EIP])
#    define BASE_REGISTER(context) (context->uc_mcontext.gregs[REG_EBP])
#    define STACK_REGISTER(context) (context->uc_mcontext.gregs[REG_ESP])
#    define THREAD_REGISTER(context) (context->uc_mcontext.gregs[REG_EBX])
#  endif

extern "C" uint64_t
vmNativeCall(void* function, void* stack, unsigned stackSize,
             unsigned returnType);

namespace vm {

inline uint64_t
dynamicCall(void* function, uintptr_t* arguments, uint8_t*,
            unsigned, unsigned argumentsSize, unsigned returnType)
{
  return vmNativeCall(function, arguments, argumentsSize, returnType);
}

} // namespace vm

#elif defined __x86_64__

#  define IP_REGISTER(context) (context->uc_mcontext.gregs[REG_RIP])
#  define BASE_REGISTER(context) (context->uc_mcontext.gregs[REG_RBP])
#  define STACK_REGISTER(context) (context->uc_mcontext.gregs[REG_RSP])
#  define THREAD_REGISTER(context) (context->uc_mcontext.gregs[REG_RBX])

extern "C" uint64_t
vmNativeCall(void* function, void* stack, unsigned stackSize,
             void* gprTable, void* sseTable, unsigned returnType);

namespace vm {

inline uint64_t
dynamicCall(void* function, uint64_t* arguments, uint8_t* argumentTypes,
            unsigned argumentCount, unsigned, unsigned returnType)
{
  const unsigned GprCount = 6;
  uint64_t gprTable[GprCount];
  unsigned gprIndex = 0;

  const unsigned SseCount = 8;
  uint64_t sseTable[SseCount];
  unsigned sseIndex = 0;

  uint64_t stack[argumentCount];
  unsigned stackIndex = 0;

  for (unsigned i = 0; i < argumentCount; ++i) {
    switch (argumentTypes[i]) {
    case FLOAT_TYPE:
    case DOUBLE_TYPE: {
      if (sseIndex < SseCount) {
        sseTable[sseIndex++] = arguments[i];
      } else {
        stack[stackIndex++] = arguments[i];
      }
    } break;

    default: {
      if (gprIndex < GprCount) {
        gprTable[gprIndex++] = arguments[i];
      } else {
        stack[stackIndex++] = arguments[i];
      }
    } break;
    }
  }

  return vmNativeCall(function, stack, stackIndex * BytesPerWord,
                      (gprIndex ? gprTable : 0),
                      (sseIndex ? sseTable : 0), returnType);
}

} // namespace vm

#else
#  error unsupported architecture
#endif

namespace vm {

inline void
trap()
{
  asm("int3");
}

inline void
memoryBarrier()
{
  __asm__ __volatile__("": : :"memory");
}

inline void
storeStoreMemoryBarrier()
{
  memoryBarrier();
}

inline void
storeLoadMemoryBarrier()
{
  memoryBarrier();
}

inline void
loadMemoryBarrier()
{
  memoryBarrier();
}

inline void
syncInstructionCache(const void*, unsigned)
{
  // ignore
}

} // namespace vm

#endif//X86_H
