/**
 * Streamify Platform Abstraction Layer (PAL) - Type Contracts & Constants
 * 
 * Every platform capability provides a descriptor: { available: boolean, reason?: string }.
 * Every PAL method degrades to a safe no-op or logged fallback. The PAL never throws into core.
 */

/**
 * @typedef {Object} Capability
 * @property {boolean} available - Whether this capability is functional on current OS/hardware
 * @property {string} [reason] - Diagnostic explanation if unavailable
 */

/**
 * @typedef {'sequential' | 'willneed' | 'dontneed'} CacheAdvice
 */

/**
 * @typedef {'normal' | 'warn' | 'critical'} PressureLevel
 */

/**
 * @typedef {Object} PressureSample
 * @property {PressureLevel} level
 * @property {number} availBytes
 * @property {number} swapPct
 * @property {number} [psiSomeAvg10]
 * @property {number} timestamp
 */

/**
 * @typedef {'wired' | 'wifi' | 'cellular' | 'loopback' | 'unknown'} NetworkMedia
 */

/**
 * @typedef {Object} NetworkClassification
 * @property {NetworkMedia} media
 * @property {number} mtu
 * @property {number} [speedMbps]
 */

export const ADVICE = Object.freeze({
  SEQUENTIAL: 'sequential',
  WILLNEED: 'willneed',
  DONTNEED: 'dontneed'
});

export const PRESSURE_LEVEL = Object.freeze({
  NORMAL: 'normal',
  WARN: 'warn',
  CRITICAL: 'critical'
});

export const NET_MEDIA = Object.freeze({
  WIRED: 'wired',
  WIFI: 'wifi',
  CELLULAR: 'cellular',
  LOOPBACK: 'loopback',
  UNKNOWN: 'unknown'
});
