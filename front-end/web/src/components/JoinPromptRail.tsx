/**
 * components/JoinPromptRail.tsx — P1-port of
 * app/…/ui/screens/JoinPromptSheet.kt (the zero-friction join prompt).
 *
 * The web lowering (§9.4 bucket B, called out in the transport ADR note —
 * lib/jam/transport.ts): the Android rails (BLE tap-to-join, LAN room
 * announce, mesh beacon, speaker connect) don't exist on the web; the
 * sighting source lowers to the INVITE LINK (`#/?jam=<code>`), the one
 * join surface the web honestly has.
 *
 * LIFECYCLE (the Kotlin sheet's rules, verbatim): the prompt renders ONLY
 * while a live sighting exists (the URL param, un-dismissed, no active
 * session); dismissal is remembered PER ROOM. Once joined, the card
 * becomes the room state surface (status, fold hash, op counters) until
 * the user leaves — the jam screens (JamSessionScreen & co) are the next
 * track; this rail is the honest interim, never a fake.
 */

import { useMemo } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { inviteRoomCode, isRoomDismissed, dismissRoom, useJamStore } from '../lib/jamStore';
import { useSessionStore } from '../lib/sessionStore';
import { Icon } from './icons';

export function JoinPromptRail(): JSX.Element | null {
  const location = useLocation();
  const navigate = useNavigate();

  const session = useSessionStore((s) => s.session);
  const status = useJamStore((s) => s.status);
  const statusDetail = useJamStore((s) => s.statusDetail);
  const handle = useJamStore((s) => s.handle);
  const roomCode = useJamStore((s) => s.roomCode);
  const stateHash = useJamStore((s) => s.stateHash);
  const opsApplied = useJamStore((s) => s.opsApplied);
  const opsRejected = useJamStore((s) => s.opsRejected);
  const error = useJamStore((s) => s.error);
  const join = useJamStore((s) => s.join);
  const leave = useJamStore((s) => s.leave);

  // The sighting: an invite-link room code, un-dismissed, no live session.
  const sighting = useMemo(() => {
    const code = inviteRoomCode(location.search);
    if (code === null) return null;
    if (isRoomDismissed(code)) return null;
    return code;
  }, [location.search]);

  // ---------------------------------------------------------------------
  // The live room state card (after join) — the honest interim surface.
  // ---------------------------------------------------------------------
  if (handle !== null && roomCode !== null) {
    const statusText =
      status === 'live'
        ? 'Live — folding the shared queue'
        : status === 'connecting'
          ? 'Connecting to the room…'
          : status === 'error'
            ? (statusDetail ?? 'Channel error')
            : status === 'closed'
              ? 'Left the room'
              : 'Idle';
    return (
      <section className="jpr jpr--live" aria-label="Jam room status">
        <div className="jpr__dot" aria-hidden="true">
          <Icon name="speaker" size={20} className={status === 'live' ? 'jpr__glyph--on' : ''} />
        </div>
        <div className="jpr__body">
          <p className="jpr__title">{roomCode}</p>
          <p className="jpr__subtitle">
            {statusText}
            {stateHash !== null ? (
              <>
                {' · '}
                <span className="jpr__hash" title={stateHash}>
                  hash {stateHash.slice(0, 12)}
                </span>
              </>
            ) : null}
          </p>
          {error !== null && <p className="jpr__error" role="alert">{error}</p>}
          <p className="jpr__meta">
            ops folded {opsApplied}
            {opsRejected > 0 ? ` · rejected ${opsRejected}` : ''}
          </p>
        </div>
        <div className="jpr__actions">
          <button type="button" className="jpr__pill" onClick={() => void leave()}>
            LEAVE
          </button>
        </div>
      </section>
    );
  }

  // ---------------------------------------------------------------------
  // The join prompt (the invite-link sighting).
  // ---------------------------------------------------------------------
  if (sighting === null) return null;
  const joinByLink = (): void => {
    if (session === null) {
      // Honest degradation: the mutation path is JWT-bound (P7). Sign-in
      // first — the prompt stays, never fakes a join.
      navigate('/profile-selection');
      return;
    }
    void join(sighting);
    // The URL param is consumed post-join so the prompt does not re-fire
    // for the same room within the page session.
    navigate('/', { replace: true });
  };

  return (
    <section className="jpr" aria-label="Join a Jam session">
      <div className="jpr__dot" aria-hidden="true">
        <Icon name="speaker" size={20} />
      </div>
      <div className="jpr__body">
        <p className="jpr__title">Join this Jam</p>
        <p className="jpr__subtitle">
          Invited by link · room {sighting.toUpperCase()}
        </p>
      </div>
      <div className="jpr__actions">
        <button
          type="button"
          className="jpr__dismiss"
          aria-label="Dismiss"
          onClick={() => {
            dismissRoom(sighting);
            navigate('/', { replace: true });
          }}
        >
          <Icon name="close" size={16} />
        </button>
        <button type="button" className="jpr__pill" onClick={joinByLink}>
          JOIN
        </button>
      </div>
    </section>
  );
}
