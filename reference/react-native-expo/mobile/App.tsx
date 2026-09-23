import { StatusBar } from 'expo-status-bar';
import { SafeAreaView, StyleSheet } from 'react-native';

import { WidgetsScreen } from './src/screens/WidgetsScreen';

export default function App() {
  return (
    <SafeAreaView style={styles.app}>
      <WidgetsScreen />
      <StatusBar style="auto" />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  app: { backgroundColor: '#fff', flex: 1 },
});
