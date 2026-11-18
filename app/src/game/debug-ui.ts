/**
 * debug-ui.ts — панель настройки коэффициентов физики в реальном времени.
 * Использует dat.gui через CDN для быстрого подбора параметров без перекомпиляции.
 */

// dat.gui подключен глобально через CDN в index.html
declare const dat: any;

import { CAR } from "./physics";

const STORAGE_KEY = "driftLabTune";

const ORIGINAL: Record<string, unknown> = {};
for (const k of Object.keys(CAR)) {
  if (typeof (CAR as Record<string, unknown>)[k] === "number") {
    ORIGINAL[k] = (CAR as Record<string, unknown>)[k];
  }
}

function saveToStorage(): void {
  const params: Record<string, unknown> = {};
  for (const k of Object.keys(CAR)) {
    if (typeof (CAR as Record<string, unknown>)[k] === "number") {
      params[k] = (CAR as Record<string, unknown>)[k];
    }
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(params));
}

function loadFromStorage(): boolean {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return false;
  try {
    const params = JSON.parse(raw);
    for (const k of Object.keys(params)) {
      if (k in CAR && typeof (CAR as Record<string, unknown>)[k] === "number") {
        (CAR as Record<string, unknown>)[k] = params[k];
      }
    }
    return true;
  } catch { return false; }
}

function exportJSON(): void {
  const params: Record<string, unknown> = {};
  for (const k of Object.keys(CAR)) {
    if (typeof (CAR as Record<string, unknown>)[k] === "number") {
      params[k] = (CAR as Record<string, unknown>)[k];
    }
  }
  const blob = new Blob([JSON.stringify(params, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = "driftlab-tune.json"; a.click();
  URL.revokeObjectURL(url);
}

function importJSON(): void {
  const input = document.createElement("input");
  input.type = "file"; input.accept = "application/json";
  input.onchange = async () => {
    const file = input.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const params = JSON.parse(text);
      for (const k of Object.keys(params)) {
        if (k in CAR && typeof (CAR as Record<string, unknown>)[k] === "number") {
          (CAR as Record<string, unknown>)[k] = params[k];
        }
      }
      location.reload();
    } catch (e) { alert("Ошибка импорта: " + (e as Error).message); }
  };
  input.click();
}

function resetAll(): void {
  for (const k of Object.keys(ORIGINAL)) {
    (CAR as Record<string, unknown>)[k] = ORIGINAL[k];
  }
  localStorage.removeItem(STORAGE_KEY);
  location.reload();
}

export function initDebugUI(): void {
  loadFromStorage();
  const gui = new dat.GUI({ width: 320 });
  gui.domElement.style.position = "fixed";
  gui.domElement.style.top = "10px";
  gui.domElement.style.right = "10px";
  gui.domElement.style.zIndex = "9999";

  const fMass = gui.addFolder("Масса и инерция");
  fMass.add(CAR, "mass", 800, 2500).step(10).name("Масса (кг)");
  fMass.add(CAR, "inertia", 800, 3000).step(10).name("Инерция (кг·м²)");
  fMass.add(CAR, "inertiaPitch", 200, 4000).step(50).name("Инерция Pitch");
  fMass.add(CAR, "inertiaRoll", 200, 4000).step(50).name("Инерция Roll");
  fMass.add(CAR, "cgHeight", 0.3, 0.9).step(0.01).name("Высота ЦТ (м)");
  fMass.open();

  const fWheels = gui.addFolder("Колёса");
  fWheels.add(CAR, "wheelRadius", 0.28, 0.4).step(0.01).name("Радиус (м)");
  fWheels.add(CAR, "wheelInertia", 0.5, 3).step(0.1).name("Инерция (кг·м²)");
  fWheels.add(CAR, "wheelDamp", 0, 5).step(0.1).name("Демпфирование");
  fWheels.add(CAR, "trackHalf", 0.6, 1.0).step(0.01).name("Полуколея (м)");
  fWheels.add(CAR, "axleFront", -2, -0.8).step(0.05).name("Передняя ось");
  fWheels.add(CAR, "axleRear", 0.8, 2).step(0.05).name("Задняя ось");
  fWheels.open();

  const fSusp = gui.addFolder("Подвеска");
  fSusp.add(CAR, "suspensionOffsetY", -0.5, 0.5).step(0.01).name("Смещение подвески Y");
  fSusp.add(CAR, "springRate", 10000, 80000).step(1000).name("Жёсткость пружины");
  fSusp.add(CAR, "damping", 1000, 10000).step(100).name("Демпфирование");
  fSusp.add(CAR, "suspensionRest", 0.05, 0.3).step(0.01).name("Длина в покое");
  fSusp.add(CAR, "suspensionMax", 0.1, 0.4).step(0.01).name("Макс. ход");
  fSusp.add(CAR, "antiRollBar", 0, 20000).step(500).name("Стабилизатор");
  fSusp.open();

  const fEngine = gui.addFolder("Двигатель");
  fEngine.add(CAR, "peakTorque", 200, 800).step(10).name("Пик момента (Н·м)");
  fEngine.add(CAR, "peakRpm", 3000, 6000).step(100).name("Пик RPM");
  fEngine.add(CAR, "idleRpm", 600, 1200).step(50).name("Холостой");
  fEngine.add(CAR, "maxRpm", 6000, 9000).step(100).name("Макс RPM");
  fEngine.add(CAR, "finalDrive", 2.5, 5).step(0.1).name("Главная пара");
  fEngine.add(CAR, "driveEff", 0.7, 1).step(0.01).name("КПД привода");

  const fBrakes = gui.addFolder("Тормоза");
  fBrakes.add(CAR, "brakeTorque", 500, 3000).step(50).name("Момент (Н·м)");
  fBrakes.add(CAR, "handbrakeTorque", 1000, 6000).step(100).name("Ручник (Н·м)");

  const fTires = gui.addFolder("Шины");
  fTires.add(CAR, "mu", 0.5, 1.5).step(0.01).name("Базовое μ");
  fTires.add(CAR, "muFront", 0.8, 1.5).step(0.01).name("μ перед");
  fTires.add(CAR, "muRear", 0.8, 1.5).step(0.01).name("μ зад");
  fTires.add(CAR, "pacejkaB", 5, 15).step(0.5).name("Пачека B");
  fTires.add(CAR, "pacejkaC", 1, 2).step(0.05).name("Пачека C");
  fTires.add(CAR, "slipB", 3, 12).step(0.5).name("Slip B");
  fTires.open();

  const fSteer = gui.addFolder("Руль");
  fSteer.add(CAR, "steerMaxLow", 0.3, 1).step(0.01).name("Макс угол (медл)");
  fSteer.add(CAR, "steerMaxHigh", 0.05, 0.3).step(0.01).name("Макс угол (быстр)");
  fSteer.add(CAR, "steerSpeedRef", 30, 80).step(5).name("Скорость реф");

  const fStab = gui.addFolder("Стабилизация");
  fStab.add(CAR, "yawDamp", 0, 1500).step(25).name("Демпфер рысканья");
  fStab.add(CAR, "tcStart", 0.2, 0.8).step(0.05).name("TC start");
  fStab.add(CAR, "tcFull", 0.6, 1.2).step(0.05).name("TC full");

  const fDrag = gui.addFolder("Сопротивление");
  fDrag.add(CAR, "dragCoef", 0.2, 0.8).step(0.01).name("Аэродинамика");

  const btns = {
    save: () => { saveToStorage(); alert("Настройки сохранены в localStorage"); },
    export: exportJSON,
    import: importJSON,
    reset: () => { if (confirm("Сбросить все настройки к исходным?")) resetAll(); },
  };
  gui.add(btns, "save").name("💾 Сохранить");
  gui.add(btns, "export").name("📤 Экспорт JSON");
  gui.add(btns, "import").name("📥 Импорт JSON");
  gui.add(btns, "reset").name("🔄 Сбросить");

  (gui as any).__controllers?.forEach((c: any) => {
    const orig = c.onFinishChange;
    c.onFinishChange = (...args: unknown[]) => {
      orig?.apply(c, args);
      saveToStorage();
    };
  });

  document.addEventListener("keydown", (e: KeyboardEvent) => {
    if (e.code === "F2") {
      gui.domElement.style.display = gui.domElement.style.display === "none" ? "block" : "none";
    }
  });

  console.log("Debug UI initialized. Press F2 to toggle.");
}
