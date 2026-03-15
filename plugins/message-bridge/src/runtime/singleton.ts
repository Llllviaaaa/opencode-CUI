import { BridgeRuntime } from './BridgeRuntime.js';
import type { PluginInput } from './types.js';
import { AppLogger } from './AppLogger.js';
import { buildClientShapeSummary } from './clientShapeSummary.js';
import { getErrorDetailsForLog, getErrorMessage } from '../utils/error.js';

let runtime: BridgeRuntime | null = null;
let initializing: Promise<BridgeRuntime> | null = null;
let lifecycleAbortController: AbortController | null = null;
let generation = 0;

export async function getOrCreateRuntime(input: PluginInput): Promise<BridgeRuntime> {
  const logger = new AppLogger(input.client, { component: 'singleton' });
  if (runtime) {
    logger.debug('runtime.singleton.reuse_existing');
    return runtime;
  }

  if (initializing) {
    logger.debug('runtime.singleton.await_initializing');
    return initializing;
  }

  logger.info('runtime.singleton.client_shape', buildClientShapeSummary(input.client));

  const candidate = new BridgeRuntime({
    workspacePath: input.worktree || input.directory,
    client: input.client,
  });
  const token = ++generation;
  lifecycleAbortController = new AbortController();

  initializing = candidate
    .start({ abortSignal: lifecycleAbortController.signal })
    .then(() => {
      if (token !== generation || lifecycleAbortController?.signal.aborted) {
        candidate.stop();
        logger.warn('runtime.singleton.initialization_cancelled');
        throw new Error('runtime_initialization_cancelled');
      }
      runtime = candidate;
      logger.info('runtime.singleton.initialized');
      return candidate;
    })
    .catch((error) => {
      runtime = null;
      logger.error('runtime.singleton.initialization_failed', {
        error: getErrorMessage(error),
        ...getErrorDetailsForLog(error),
      });
      throw error;
    })
    .finally(() => {
      initializing = null;
    });

  return initializing;
}

export function getRuntime(): BridgeRuntime | null {
  return runtime;
}

export function stopRuntime(): void {
  generation += 1;

  if (initializing) {
    lifecycleAbortController?.abort();
    initializing = null;
  }

  if (!runtime) {
    lifecycleAbortController = null;
    return;
  }

  runtime.stop();
  runtime = null;
  lifecycleAbortController = null;
}

export function __resetRuntimeForTests(): void {
  stopRuntime();
}
