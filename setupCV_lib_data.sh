#bin/sh

bin/hadoop fs -copyFromLocal ~/Projects/opencv-2.4.4/release/bin/libopencv_java244.so hdfs://localhost:9000/libraries/libopencv_java244.so
echo "Copy libopencv_java244.so -- done"

bin/hadoop fs -mkdir imgsamples
bin/hadoop fs -put ./CombineOfflineMR/imgsamples/* imgsamples/
echo "Copy image samples --- done"

#bin/hadoop jar ./CombineOfflineMR/offline.jar myorg.offline.CombineOfflineMR -libjars ./CombineOfflineMR/opencv-244.jar -files ./CombineOfflineMR/cars3.xml imgsamples/16m_1,imgsamples/16m_2 output1
