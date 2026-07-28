export type PersistentBattleSave = {
  schema: "cobbleverse-pve-battle-save";
  version: 1;
  battleEngine: "cobbleverse" | "showdown";
  scenario: unknown;
  turn: number;
  savedAt: string;
  payload: unknown;
};

export type PersistentBattleSlot = {
  slot: number;
  save: PersistentBattleSave;
};

const DATABASE_NAME = "cobbleverse-battle-lab";
const DATABASE_VERSION = 1;
const STORE_NAME = "pve-battle-saves";

function openDatabase() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) {
        database.createObjectStore(STORE_NAME, { keyPath: "slot" });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function completeTransaction(transaction: IDBTransaction) {
  return new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export async function listPersistentBattleSlots() {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, "readonly");
    const completion = completeTransaction(transaction);
    const request = transaction.objectStore(STORE_NAME).getAll();
    const slots = await new Promise<PersistentBattleSlot[]>((resolve, reject) => {
      request.onsuccess = () => resolve(request.result as PersistentBattleSlot[]);
      request.onerror = () => reject(request.error);
    });
    await completion;
    return slots.sort((left, right) => left.slot - right.slot);
  } finally {
    database.close();
  }
}

export async function getPersistentBattleSlot(slot: number) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, "readonly");
    const completion = completeTransaction(transaction);
    const request = transaction.objectStore(STORE_NAME).get(slot);
    const result = await new Promise<PersistentBattleSlot | undefined>(
      (resolve, reject) => {
        request.onsuccess = () =>
          resolve(request.result as PersistentBattleSlot | undefined);
        request.onerror = () => reject(request.error);
      },
    );
    await completion;
    return result;
  } finally {
    database.close();
  }
}

export async function putPersistentBattleSlot(
  slot: number,
  save: PersistentBattleSave,
) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    const completion = completeTransaction(transaction);
    transaction.objectStore(STORE_NAME).put({ slot, save });
    await completion;
  } finally {
    database.close();
  }
}

export async function deletePersistentBattleSlot(slot: number) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    const completion = completeTransaction(transaction);
    transaction.objectStore(STORE_NAME).delete(slot);
    await completion;
  } finally {
    database.close();
  }
}
