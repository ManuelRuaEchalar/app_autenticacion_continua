Estos assets los genera backend/export_tflite_model.py; no se editan a mano.

Ficheros esperados:
  auth_fedper.tflite      modelo FedPer con firmas entrenables on-device
  model_manifest.json     contrato: formas, encoder_flat_size, hiperparámetros
  scaler_stats.json       media/escala del StandardScaler global de mejor.py
  background_train.bin    pool de impostores para entrenar y evaluar
  background_calib.bin    pool DISJUNTO, sólo para calibrar el umbral

Generación:

  cd backend
  python export_tflite_model.py \
      --encoder-weights   checkpoints_fl/best_encoder_weights.npz \
      --scaler-stats      scaler_stats.json \
      --background-train  bg_train_scaled.npy \
      --background-calib  bg_calib_scaled.npy \
      --android-assets-dir ../autenticacionContinua/app/src/main/assets

Sin ellos la app falla al arrancar el módulo de Koin, a propósito: es
preferible a entrenar contra un modelo incompatible y contaminar la
agregación del resto de clientes.

startModel.tflite (arquitectura DeepConvLSTM anterior) se eliminó en la
refactorización a FedPer. No es compatible con el cliente actual.
