/**
 * physics.ts — физика автомобиля: raycast-подвеска, шины по Пачеке, RWD, ручник.
 * Координаты: плоскость XZ, ось Y вверх. Поворот руля: yaw = 0 → машина смотрит в −Z.
 */

export interface CarControls {
  throttle: number; brake: number; steer: number; handbrake: boolean;
}

export interface GroundInfo {
  height: number; // высота поверхности
  grip: number; roll: number; dragMul: number;
  lat: number; nx: number; nz: number; cx: number; cz: number;
}

export const CAR = {
  mass: 1150, inertia: 877, inertiaPitch: 681, inertiaRoll: 370,
  axleFront: -1.235, axleRear: 1.235, trackHalf: 0.72, cgHeight: 0.5,
  wheelRadius: 0.32, wheelInertia: 1.5, wheelDamp: 1.0,
  peakTorque: 274, peakRpm: 4800, idleRpm: 800, maxRpm: 7500,
  gears: [0, 3.5, 2.25, 1.55, 1.18, 0.95], reverseRatio: 3.0, finalDrive: 4.083, driveEff: 0.85,
  brakeTorque: 1200, handbrakeTorque: 3500, dragCoef: 0.39,
  steerMaxLow: 0.6, steerMaxHigh: 0.6, steerSpeedRef: 25,
  pacejkaB: 10, pacejkaC: 1.3, slipB: 7, mu: 1.1, muFront: 1.12, muRear: 1.08,
  yawDamp: 400, tcStart: 0.3, tcFull: 0.8,
  // ПОДВЕСКА
  springRate: 35000, damping: 3888, suspensionRest: 0.15, suspensionMax: 0.25, antiRollBar: 10000,
};

const G = 9.81;
const WHEEL_LX = [-CAR.trackHalf, CAR.trackHalf, -CAR.trackHalf, CAR.trackHalf];
const WHEEL_LZ = [CAR.axleFront, CAR.axleFront, CAR.axleRear, CAR.axleRear];

function clamp(v: number, lo: number, hi: number): number { return v < lo ? lo : v > hi ? hi : v; }
function approach(v: number, target: number, maxDelta: number): number {
  const d = target - v; return Math.abs(d) <= maxDelta ? target : v + Math.sign(d) * maxDelta;
}
function pacejka(x: number, B: number, C: number): number { return Math.sin(C * Math.atan(B * x)); }
function torqueCurve(rpm: number): number {
  const x = (rpm - CAR.peakRpm) / 2900; return clamp(1 - x * x, 0.42, 1);
}

export class CarPhysics {
  x = 0; z = 0; yaw = 0; vx = 0; vz = 0; omega = 0; steer = 0;
  // 3D кузов
  y = CAR.cgHeight; pitch = 0; roll = 0; vy = 0; omegaPitch = 0; omegaRoll = 0;
  readonly wheelSpin = [0, 0, 0, 0]; readonly wheelOmega = [0, 0, 0, 0];
  readonly wheelCompression = [0, 0, 0, 0];
  gear = 1; rpm: number = CAR.idleRpm; private shiftTimer = 0; private rearSlip = 0;
  speed = 0; slipAngle = 0; drifting = false; lastImpact = 0; grass = false;

  reset(x: number, z: number, yaw: number): void {
    this.x = x; this.z = z; this.yaw = yaw; this.vx = 0; this.vz = 0; this.omega = 0; this.steer = 0;
    // Равновесная высота: подвеска сжата ровно настолько, чтобы держать вес машины
    const eqCompression = (CAR.mass * G) / (4 * CAR.springRate);
    const hubLocalY = CAR.cgHeight - (CAR.suspensionRest + CAR.wheelRadius);
    this.y = (CAR.suspensionMax + CAR.wheelRadius - eqCompression) - hubLocalY;
    this.pitch = 0; this.roll = 0; this.vy = 0; this.omegaPitch = 0; this.omegaRoll = 0;
    this.wheelSpin.fill(0); this.wheelOmega.fill(0); this.wheelCompression.fill(eqCompression);
    this.gear = 1; this.rpm = CAR.idleRpm; this.shiftTimer = 0; this.rearSlip = 0;
    this.speed = 0; this.slipAngle = 0; this.drifting = false; this.lastImpact = 0;
  }

  shiftUp(): void {
    if (this.gear === -1) this.gear = 1;
    else if (this.gear < 5) { this.gear += 1; this.shiftTimer = 0.15; }
  }
  shiftDown(): void {
    if (this.gear > 1) { this.gear -= 1; this.shiftTimer = 0.15; }
    else if (this.gear === 1 && this.speed < 2) { this.gear = -1; this.shiftTimer = 0.15; }
  }

  update(dt: number, ctl: CarControls, ground: (x: number, z: number, out: GroundInfo) => void, gOut: GroundInfo): void {
    const SUB = 3; const h = dt / SUB; this.lastImpact = 0;
    for (let s = 0; s < SUB; s++) this.substep(h, ctl, ground, gOut);
  }

  private substep(h: number, ctl: CarControls, ground: (x: number, z: number, out: GroundInfo) => void, g: GroundInfo): void {
    const sin = Math.sin(this.yaw), cos = Math.cos(this.yaw);
    const fx = -sin, fz = -cos, rx = -fz, rz = fx;

    this.speed = Math.hypot(this.vx, this.vz);
    const sf = clamp(this.speed / CAR.steerSpeedRef, 0, 1);
    const maxSteer = CAR.steerMaxLow + (CAR.steerMaxHigh - CAR.steerMaxLow) * sf;
    const steerTarget = -ctl.steer * maxSteer;
    const steerSpeed = 0.85;
    this.steer = approach(this.steer, steerTarget, steerSpeed * h);
    if (this.shiftTimer > 0) this.shiftTimer -= h;

    const ratio = this.gear === -1 ? -CAR.reverseRatio : CAR.gears[this.gear];
    const wRear = 0.5 * (this.wheelOmega[2] + this.wheelOmega[3]);
    let rpm = Math.abs(wRear * ratio * CAR.finalDrive) * 60 / (2 * Math.PI);
    if (ctl.throttle > 0.15) {
      const slipBlend = Math.max(0, 1 - this.speed / 8);
      rpm = Math.max(rpm, CAR.idleRpm + ctl.throttle * (CAR.maxRpm - CAR.idleRpm) * 0.62 * slipBlend);
    } else { rpm = Math.max(rpm, CAR.idleRpm); }
    rpm = Math.min(rpm, CAR.maxRpm + 400);
    let engineTq = CAR.peakTorque * torqueCurve(rpm) * ctl.throttle;
    if (this.shiftTimer > 0) engineTq = 0;
    const tc = clamp((this.rearSlip - CAR.tcStart) / (CAR.tcFull - CAR.tcStart), 0, 1);
    engineTq *= 1 - 0.5 * tc;
    if (rpm > CAR.maxRpm) engineTq *= 0.15;
    this.rpm = rpm;

    const hubLocalY = CAR.cgHeight - (CAR.suspensionRest + CAR.wheelRadius);
    const fzArray = [0, 0, 0, 0];

    for (let i = 0; i < 4; i++) {
      const lx = WHEEL_LX[i], lz = WHEEL_LZ[i];
      const isFront = i < 2, isDriven = i >= 2;
      const px = lx * cos + lz * sin, pz = -lx * sin + lz * cos;
      const wx = this.x + px, wz = this.z + pz;
      ground(wx, wz, g);

      // Raycast подвеска
      const yHub = this.y + hubLocalY + lx * this.roll - lz * this.pitch;
      const distToGround = yHub - g.height;
      const rayLength = CAR.suspensionMax + CAR.wheelRadius;
      let Fz = 0;

      if (distToGround < rayLength) {
        let c = rayLength - distToGround;
        c = clamp(c, 0, CAR.suspensionMax);
        this.wheelCompression[i] = c;
        const vRel = this.vy + this.omegaPitch * lz - this.omegaRoll * lx;
        Fz = CAR.springRate * c + CAR.damping * vRel;
      } else {
        this.wheelCompression[i] = 0;
      }
      fzArray[i] = Fz;
    }

    // Стабилизатор поперечной устойчивости
    const arbFront = CAR.antiRollBar * (this.wheelCompression[0] - this.wheelCompression[1]);
    const arbRear = CAR.antiRollBar * (this.wheelCompression[2] - this.wheelCompression[3]);
    fzArray[0] += arbFront; fzArray[1] -= arbFront;
    fzArray[2] += arbRear; fzArray[3] -= arbRear;
    for (let i = 0; i < 4; i++) fzArray[i] = Math.max(0, fzArray[i]);

    let FxTot = 0, FzTot = 0, tauYaw = 0, rearSigSum = 0, F_up_Tot = 0;

    for (let i = 0; i < 4; i++) {
      const lx = WHEEL_LX[i], lz = WHEEL_LZ[i];
      const isFront = i < 2, isDriven = i >= 2;
      const px = lx * cos + lz * sin, pz = -lx * sin + lz * cos;
      ground(this.x + px, this.z + pz, g);

      const Fz = fzArray[i];
      const mu = (isFront ? CAR.muFront : CAR.muRear) * g.grip;
      const loadSens = clamp(1 - 0.000015 * (Fz - 3300), 0.75, 1.1);
      const D = mu * Fz * loadSens;

      const steerAngle = isFront ? this.steer : 0;
      const phi = this.yaw + steerAngle;
      const fwx = -Math.sin(phi), fwz = -Math.cos(phi), rwx = -fwz, rwz = fwx;
      const vwx = this.vx + this.omega * pz, vwz = this.vz - this.omega * px;
      const vLong = vwx * fwx + vwz * fwz, vLat = vwx * rwx + vwz * rwz;

      const alpha = Math.atan2(vLat, Math.max(Math.abs(vLong), 1.0));
      const w = this.wheelOmega[i];
      const sigma = (w * CAR.wheelRadius - vLong) / Math.max(Math.abs(vLong), 1.0);
      if (isDriven) rearSigSum += sigma;

      let Fxi = D * pacejka(sigma, CAR.slipB, CAR.pacejkaC);
      let Fyi = D * pacejka(alpha, CAR.pacejkaB, CAR.pacejkaC);
      const sum = Math.abs(Fxi) + Math.abs(Fyi);
      if (sum > D) { const k = D / sum; Fxi *= k; Fyi *= k; }

      if (ctl.handbrake && isDriven) { Fxi = clamp(-vLong * 55, -D * 0.95, D * 0.95); Fyi *= 0.55; }

      const Fwx = Fxi * fwx - Fyi * rwx, Fwz = Fxi * fwz - Fyi * rwz;
      FxTot += Fwx; FzTot += Fwz; tauYaw += pz * Fwx - px * Fwz;

      let Tw = 0;
      if (isDriven && this.shiftTimer <= 0) Tw = (engineTq * ratio * CAR.finalDrive * CAR.driveEff) / 2;
      if (ctl.brake > 0.01) Tw -= ctl.brake * (CAR.brakeTorque / 4) * Math.tanh(w * 10);
      if (ctl.handbrake && isDriven) Tw -= CAR.handbrakeTorque * Math.tanh(w * 10);
      
      const dw = (Tw - Fxi * CAR.wheelRadius - CAR.wheelDamp * w) / CAR.wheelInertia;
      this.wheelOmega[i] += dw * h;
      if (Math.abs(this.wheelOmega[i]) < 0.02 && Math.abs(Tw) < 1) this.wheelOmega[i] = 0;
      this.wheelSpin[i] += this.wheelOmega[i] * h;

      F_up_Tot += Fz;
    }

    this.rearSlip += (rearSigSum / 2 - this.rearSlip) * 0.3;

    const spd = this.speed;
    if (spd > 0.01) {
      const inv = 1 / spd;
      const fAero = CAR.dragCoef * spd * spd * g.dragMul;
      const fRoll = g.roll * CAR.mass * G;
      const fr = (fAero + fRoll) * inv;
      FxTot -= this.vx * fr; FzTot -= this.vz * fr;
      this.grass = g.grip < 0.8;
    } else { this.grass = false; }

    if (spd < 0.4 && ctl.throttle < 0.05) {
      const damp = Math.exp(-6 * h); this.vx *= damp; this.vz *= damp;
    }

    // Интегрирование 2D
    const ax = FxTot / CAR.mass, az = FzTot / CAR.mass;
    this.vx += ax * h; this.vz += az * h;
    this.omega += ((tauYaw - CAR.yawDamp * this.omega) / CAR.inertia) * h;
    this.omega = clamp(this.omega, -6, 6);
    this.yaw += this.omega * h;
    this.x += this.vx * h; this.z += this.vz * h;

    // Простая вертикальная динамика (без сложного наклона кузова)
    F_up_Tot -= CAR.mass * G;
    this.vy += (F_up_Tot / CAR.mass) * h;
    this.y += this.vy * h;
    if (this.y < CAR.wheelRadius + 0.01) { 
      this.y = CAR.wheelRadius + 0.01; 
      this.vy = Math.max(0, this.vy); 
    }
    // Визуальный наклон будет рассчитан в boot.ts на основе ускорений

    ground(this.x, this.z, g);
    const wallLat = 9.2;
    if (Math.abs(g.lat) > wallLat) {
      const over = Math.abs(g.lat) - wallLat;
      this.x -= g.nx * over; this.z -= g.nz * over;
      const vn = this.vx * g.nx + this.vz * g.nz;
      if (vn > 0) { this.lastImpact = vn; this.vx -= g.nx * vn * 1.35; this.vz -= g.nz * vn * 1.35; this.omega *= 0.92; }
    }

    this.speed = Math.hypot(this.vx, this.vz);
    this.slipAngle = Math.atan2(this.vx * rx + this.vz * rz, Math.abs(this.vx * fx + this.vz * fz) + 0.001);
    this.drifting = this.speed > 5 && Math.abs(this.slipAngle) > 0.2 && Math.abs(this.slipAngle) < 1.3;
  }
}
