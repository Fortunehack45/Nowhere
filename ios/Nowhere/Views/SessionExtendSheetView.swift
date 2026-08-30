import SwiftUI

struct SessionExtendSheetView: View {

    @Environment(\.presentationMode) var presentationMode
    @ObservedObject var timerManager = SessionTimerManager.shared
    @State private var isExtending: Bool = false

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Spacer()

                // Timer Visualizer
                VStack(spacing: 8) {
                    Image(systemName: "timer")
                        .font(.system(size: 48, weight: .bold))
                        .foregroundColor(.red)

                    Text(timerManager.formattedTimeRemaining)
                        .font(.system(size: 36, weight: .black, design: .monospaced))
                        .foregroundColor(.white)

                    Text(timerManager.isExpired ? "SESSION EXPIRED" : "ACTIVE SPOOFING SESSION")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(timerManager.isExpired ? .red : .green)
                }
                .padding(24)
                .frame(maxWidth: .infinity)
                .background(Color(white: 0.12))
                .cornerRadius(20)
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.1), lineWidth: 1))
                .padding(.horizontal)

                VStack(spacing: 12) {
                    Button(action: {
                        isExtending = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                            timerManager.extendSession(additionalSeconds: 7200)
                            isExtending = false
                            presentationMode.wrappedValue.dismiss()
                        }
                    }) {
                        HStack {
                            if isExtending {
                                ProgressView().scaleEffect(0.8)
                            } else {
                                Image(systemName: "play.rectangle.fill")
                                    .foregroundColor(.white)
                            }
                            Text(isExtending ? "Granting +2 Hours..." : "Watch Video (+2 Hours Free)")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(Color.red)
                        .cornerRadius(12)
                    }

                    Button(action: {
                        timerManager.startSession(duration: 7200)
                        presentationMode.wrappedValue.dismiss()
                    }) {
                        Text("Reset to 2 Hours")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.gray)
                    }
                }
                .padding(.horizontal)

                Spacer()
            }
            .background(Color(white: 0.08).ignoresSafeArea())
            .navigationTitle("Session Management")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") {
                        presentationMode.wrappedValue.dismiss()
                    }
                    .foregroundColor(.red)
                }
            }
        }
    }
}
